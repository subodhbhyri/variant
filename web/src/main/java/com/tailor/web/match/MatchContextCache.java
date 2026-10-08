package com.tailor.web.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tailor.engine.fonts.FontMap;
import com.tailor.engine.generate.SkillsDictionary;
import com.tailor.engine.match.Embedder;
import com.tailor.engine.match.MatchRunner;
import com.tailor.engine.render.Renderer;
import com.tailor.web.generation.LibraryRepository;
import com.tailor.web.generation.LibraryService;
import com.tailor.web.jobs.ConditionalOnJobHandlers;
import com.tailor.web.resumes.Resume;
import com.tailor.web.storage.FileStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * One engine {@link MatchRunner.Context} per (resume, library version), kept in the worker for a few
 * recent ones: the onboarding outputs and the library are downloaded and measured once, then every
 * posting for that library reuses them (the Phase 5 batch does the same). A context is used by one
 * job at a time. The context's directory holds the user's resume, so it is deleted on eviction.
 */
@Component
@ConditionalOnJobHandlers
public class MatchContextCache implements DisposableBean {

    /** What to do with a ready context and the directory its files are in. */
    @FunctionalInterface
    public interface Work<T> {
        T run(MatchRunner.Context context, Path contextDir) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(MatchContextCache.class);
    private static final int MAX_ENTRIES = 6;
    private static final ObjectMapper SNAKE = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private record Key(UUID resumeId, UUID libraryId) {
    }

    private static final class Entry {
        final Path dir;
        final MatchRunner.Context context;
        final ReentrantLock lock = new ReentrantLock();

        Entry(Path dir, MatchRunner.Context context) {
            this.dir = dir;
            this.context = context;
        }
    }

    private final FileStorage storage;
    private final LibraryService libraries;
    private final Embedder embedder;
    private final Renderer renderer;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public MatchContextCache(FileStorage storage, LibraryService libraries, Embedder embedder, Renderer renderer) {
        this.storage = storage;
        this.libraries = libraries;
        this.embedder = embedder;
        this.renderer = renderer;
    }

    public <T> T with(Resume resume, LibraryRepository.Library library, Work<T> work) throws Exception {
        Entry entry = obtain(resume, library);
        entry.lock.lock();
        try {
            return work.run(entry.context, entry.dir);
        } finally {
            entry.lock.unlock();
        }
    }

    private synchronized Entry obtain(Resume resume, LibraryRepository.Library library) throws Exception {
        Key key = new Key(resume.id(), library.id());
        Entry existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        Entry built = build(resume, library);
        entries.put(key, built);
        evict();
        return built;
    }

    private Entry build(Resume resume, LibraryRepository.Library library) throws Exception {
        Path dir = Files.createTempDirectory("match-context-");
        try {
            Path docx = dir.resolve("normalized.docx");
            Files.write(docx, storage.get(resume.normalizedKey()));
            Files.write(dir.resolve("preview.pdf"), storage.get(resume.previewKey()));
            Files.write(dir.resolve("baseline.json"), storage.get(resume.baselineKey()));
            // The engine reads the onboarding outputs from beside the document; onboard.json is rebuilt from the row.
            Files.writeString(dir.resolve("onboard.json"), PLAIN.writeValueAsString(resume.onboardJson()));

            LibraryService.EngineLibrary lib = libraries.engineLibrary(library.id(), resume.userId());
            Files.writeString(dir.resolve("variants.json"), SNAKE.writeValueAsString(Map.of("jobs", lib.jobs())));
            Files.writeString(dir.resolve("library.json"), SNAKE.writeValueAsString(lib.projects()));

            MatchRunner.Context context = MatchRunner.buildContext(docx, dir.resolve("variants.json"),
                    dir.resolve("library.json"), SkillsDictionary.loadDefault(), embedder, renderer, FontMap.loadDefault());
            log.info("match context ready for resume {} library version {}", resume.id(), library.version());
            return new Entry(dir, context);
        } catch (Exception e) {
            deleteTree(dir);
            throw e;
        }
    }

    /** Drops the least recently used contexts nobody is using, past {@value #MAX_ENTRIES}. */
    private void evict() {
        Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > MAX_ENTRIES && it.hasNext()) {
            Map.Entry<Key, Entry> eldest = it.next();
            if (eldest.getValue().lock.tryLock()) {
                try {
                    it.remove();
                    deleteTree(eldest.getValue().dir);
                } finally {
                    eldest.getValue().lock.unlock();
                }
            }
        }
    }

    @Override
    public synchronized void destroy() {
        entries.values().forEach(e -> deleteTree(e.dir));
        entries.clear();
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}

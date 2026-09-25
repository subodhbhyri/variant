package com.tailor.engine.docx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A namespace-aware, XXE-hardened DOM parser for OOXML parts. Used for
 * bullet detection (spec section 4) and editing (section 5), where a DOM
 * walk is required rather than regex — unlike font normalization (section 3),
 * which only rewrites attribute values and stays regex-based on purpose
 * (see FontNormalizer's javadoc).
 *
 * <p>PHASE2_SPEC.md 2.1 check 12 / 2.1.1: every part must parse with element
 * depth &le; {@link #MAX_ELEMENT_DEPTH}. A SAX pre-pass enforces this
 * independent of whether the JAXP {@code maxElementDepth} attribute is
 * honored by the platform's DOM implementation.
 */
public final class SafeXml {

    private static final int MAX_ELEMENT_DEPTH = 100;

    private SafeXml() {
    }

    public static Document parse(byte[] xmlBytes) throws IOException {
        checkDepth(xmlBytes);
        try {
            DocumentBuilder builder = newBuilder();
            return builder.parse(new ByteArrayInputStream(xmlBytes));
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("failed to parse XML part", e);
        }
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        trySetMaxElementDepth(factory);

        return factory.newDocumentBuilder();
    }

    private static void trySetMaxElementDepth(DocumentBuilderFactory factory) {
        try {
            factory.setAttribute(
                    "http://www.oracle.com/xml/jaxp/properties/maxElementDepth", MAX_ELEMENT_DEPTH);
        } catch (IllegalArgumentException ignored) {
            // Not recognized by this JAXP implementation; checkDepth's SAX
            // pre-pass below is the authoritative limit either way.
        }
    }

    /**
     * Streaming depth check via SAX, done before the DOM builder ever sees the
     * bytes. Same hardening as {@link #newBuilder}, so a malformed/entity-laden
     * part fails here the same way it would fail DOM parsing.
     */
    private static void checkDepth(byte[] xmlBytes) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new ByteArrayInputStream(xmlBytes), new DepthHandler());
        } catch (DepthExceededException e) {
            throw new IOException(
                    "XML element depth exceeds " + MAX_ELEMENT_DEPTH, e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("failed to parse XML part", e);
        }
    }

    private static final class DepthExceededException extends SAXException {
        DepthExceededException() {
            super("element depth exceeds " + MAX_ELEMENT_DEPTH);
        }
    }

    private static final class DepthHandler extends DefaultHandler {
        private int depth = 0;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            depth++;
            if (depth > MAX_ELEMENT_DEPTH) {
                throw new DepthExceededException();
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            depth--;
        }
    }
}

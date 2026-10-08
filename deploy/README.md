# Putting Variant on a server — step by step

This is for you (the owner). It takes you from nothing to a running server you can sign in to. It costs $0 per month on
Oracle Cloud's Always Free tier. Allow an afternoon, plus the time the ARM check takes (step 9).

What you will end up with: one Ubuntu server running five containers — Caddy (web server with automatic HTTPS),
the api, the worker, the renderer (LibreOffice, with no internet access), and Postgres. Only ports 80 and 443 (and SSH,
22) are open to the world. Files and the database live on the server; an encrypted backup is made every night, and you
copy it to your own computer.

> **What has and has not been tested.** Everything in `deploy/` was exercised on a Windows machine with Docker by
> `scripts/deploy-test.ps1`: the production compose stack, the exposure checks (P6-T19), the sign-in page, a full user
> journey through Caddy, and a backup, destroy and restore round trip (P6-T17). What has **not** been tested is the
> real server itself: `install.sh` on a fresh Ubuntu machine, Oracle's firewall, Let's Encrypt, and — most importantly —
> the **ARM check** (step 9). Those are the first things to watch when you follow this guide.

The web app (the screens you click through) is a separate piece of work (6B). Until it exists, the server shows a
placeholder page at `/`, and the api answers at `/api/...`. Everything below works without it.

---

## 0. What you need on your Windows computer

- **PowerShell** and **OpenSSH** (both are in Windows 11; check with `ssh -V`).
- **age** (the backup encryption tool): download `age-v1.x-windows-amd64.zip` from
  <https://github.com/FiloSottile/age/releases>, unzip it, and put `age.exe` and `age-keygen.exe` in a folder on your PATH.
- **git**.

## 1. Make your backup key (once, on your own computer)

```powershell
age-keygen -o $HOME\variant-backup-key.txt
```

It prints `Public key: age1...`. **Copy that public key line.** The file `variant-backup-key.txt` is the private key:
put a copy on a USB stick or in your password manager. **It must never be on the server.** Without it, backups cannot
be opened — by anyone, including you.

## 2. Make an SSH key (once)

```powershell
ssh-keygen -t ed25519 -f $HOME\.ssh\variant_ed25519
```

Press Enter for no passphrase (or set one). `variant_ed25519.pub` is the public half you will give to the server.

## 3. Create the server (Oracle Cloud Always Free)

1. Sign up at <https://www.oracle.com/cloud/free/> (a card is needed for identity; the Always Free resources are not
   charged). Choose a home region close to your users — it cannot be changed later.
2. **Compute → Instances → Create instance.**
   - Image: **Canonical Ubuntu 24.04** (the aarch64 / ARM build is chosen automatically with the shape below).
   - Shape: **Ampere → VM.Standard.A1.Flex**, **2 OCPU, 12 GB memory**.
   - Networking: keep the default VCN; make sure **Assign a public IPv4 address** is on.
   - SSH keys: **Paste public keys** → paste the contents of `variant_ed25519.pub`.
   - Create. If it says *Out of capacity for shape A1*, try again in a few hours, or pick another availability domain.
     If it never works, use the x86 fallback in the spec (a small 4 GB x86 server): everything here is the same, minus the
     ARM check in step 9.
3. When it is running, note its **public IP address**.
4. **Open the ports in Oracle's firewall (this is the step everyone misses).** Instance → the subnet link → the
   **security list** → **Add ingress rules**: source `0.0.0.0/0`, protocol TCP, destination port `80`; add another for `443`.
   (Port 22 is already there.) Oracle also has a firewall *inside* the server; `install.sh` handles that one.

Log in to check it works:

```powershell
ssh -i $HOME\.ssh\variant_ed25519 ubuntu@<the public IP>
```

## 4. Get a free host name (DuckDNS)

1. Go to <https://www.duckdns.org>, sign in, and create a name, e.g. `myvariant` → `myvariant.duckdns.org`.
2. Put the server's public IP in the "current ip" box and click **update ip**.

Wait a minute, then on your computer `nslookup myvariant.duckdns.org` should show that IP. (Let's Encrypt needs this to
work before the server can get its HTTPS certificate.)

## 5. Set up "Sign in with Google"

Email sign-in needs a domain to send mail from, so version 1 uses Google only.

1. <https://console.cloud.google.com> → create a project (e.g. `variant`).
2. **APIs & Services → OAuth consent screen** → External → fill in the app name and your email → add yourself under
   **Test users** (the app can stay in "Testing" for a private beta; up to 100 test users).
3. **APIs & Services → Credentials → Create credentials → OAuth client ID** → type **Web application**.
   - **Authorized redirect URIs:** `https://myvariant.duckdns.org/api/auth/google/callback` (your own host name; it must
     match exactly, including `/api`).
4. Copy the **client ID** and **client secret** — step 8 needs them.

## 6. Get an Anthropic API key

<https://console.anthropic.com> → API keys → create one, and add a little credit. Generation costs a few cents per
résumé library. The worker is the only part that uses it.

## 7. Put the code on the server

The repository is private, so the server gets its own **read-only deploy key**. On the server (`ssh ... ubuntu@IP`):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/variant_deploy -N ""
cat ~/.ssh/variant_deploy.pub
```

Copy that line. On GitHub: the repository → **Settings → Deploy keys → Add deploy key** → paste it, leave "Allow write
access" **unticked**. Then, still on the server:

```bash
sudo install -d -o ubuntu -g ubuntu /opt/variant
GIT_SSH_COMMAND="ssh -i ~/.ssh/variant_deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
  git clone git@github.com:subodhbhyri/variant.git /opt/variant
cd /opt/variant
sudo deploy/install.sh
```

`install.sh` installs Docker, opens ports 22/80/443 in the server's own firewall, turns on automatic security updates,
makes SSH key-only with no root login, creates the `variant` user (and gives it the deploy key and your SSH key), adds a
swap file on small machines, and schedules the nightly backup. It is safe to run again. When it finishes, log out and
log back in **as `variant`**:

```powershell
ssh -i $HOME\.ssh\variant_ed25519 variant@<the public IP>
cd /opt/variant
```

## 8. Fill in `deploy/.env`

`install.sh` made `deploy/.env` from the example, readable only by `variant`. Edit it:

```bash
nano deploy/.env
```

| Setting | What to put |
|---|---|
| `APP_HOST` | `myvariant.duckdns.org` |
| `PROFILE` | `standard` for the 12 GB machine, `small` for a 4 GB one (`install.sh` already set it) |
| `POSTGRES_PASSWORD` | a long random string: `openssl rand -base64 33 \| tr -d '/+='` |
| `APP_STORAGE_LINK_SECRET` | another one |
| `ANTHROPIC_API_KEY` | from step 6 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | from step 5 |
| `BACKUP_AGE_RECIPIENT` | the **public** key line from step 1 (`age1...`) |

Leave `APP_MAIL_MODE=off`. Never commit this file (it is ignored by git) and never paste it into chat or email.

## 9. ARM servers only: the ARM check

Every measurement behind the product's promise — "nothing on the page moves" — was made with LibreOffice on an
x86 computer. The ARM server must prove it gives the **same** results before you trust it. `deploy.sh` will refuse
to start on ARM until this has passed. (Check which you have: `uname -m` prints `aarch64` for ARM, `x86_64` for x86.
**On x86, skip this step.**)

The check needs the 9 corpus résumés, which are real people's data. They are not in the repository. Copy them up only
for this check — from your Windows computer:

```powershell
scp -i $HOME\.ssh\variant_ed25519 -r C:\Users\ashok\javaworld\resume-tailor-step1.5b\corpus variant@<IP>:~/corpus
```

Then on the server (this builds the images for ARM first, so it takes a good while; the run is about 30 minutes):

```bash
cd /opt/variant
deploy/arm-gate.sh ~/corpus --shred
```

`--shred` overwrites and deletes the copied résumés when it ends, pass or fail. It runs `corpus-check` on all 9
résumés and the P5-T17 identity check on the 3 fixture postings, inside the ARM images.

- It ends with **`ARM GATE PASSED`**: continue.
- It ends with an error: **do not use this server for real users.** Copy the output and tell the project owner (me,
  in the next session), and use an x86 server instead. Nothing is lost: only the check failed.

The pass is recorded for this exact engine/renderer/Docker code. If a later update changes any of those folders,
`deploy.sh` will ask for the check again.

## 10. Deploy

```bash
cd /opt/variant
deploy/deploy.sh
```

It checks `.env`, fetches the code, builds the images (first time: 10–20 minutes), starts everything and waits until all
five services report healthy. The database is created and upgraded automatically as the api starts. At the end it
tells you whether `https://myvariant.duckdns.org/api/healthz` answers. The first time, Caddy needs about a minute to get
its certificate.

Check from your computer: open `https://myvariant.duckdns.org/api/config` — you should see `{"signin":["google"]}`.

## 11. First sign-in and the real-server test (P6-T13)

The web app is not built yet, so sign in through the api directly: open
`https://myvariant.duckdns.org/api/auth/google` in your browser, choose your Google account (the one you added as a test
user). You will land on the placeholder page, and `https://myvariant.duckdns.org/api/me` now shows your account.

To run the full journey and time it against the spec's latency targets, copy your `SESSION` cookie (browser
developer tools → Application → Cookies → the `SESSION` value) and, from your Windows computer:

```powershell
.\scripts\smoke-test.ps1 -BaseUrl https://myvariant.duckdns.org/api -SessionCookie <value> -Latency
```

It uploads the sample résumé, generates (real Anthropic calls: a few cents), matches the three sample postings,
renders an alternative, makes an edit and downloads every PDF. Resume #1 should arrive in about 8 seconds at the median
(15 at the 95th percentile). Send me the output if anything is slower or fails.

## 12. Backups

- **Nightly** at 03:15 the server makes an encrypted backup into `/var/backups/variant` and keeps the last 7. Check it
  ran: `ls -lh /var/backups/variant` and `journalctl -t variant-backup`.
- **Copy it to your own computer — at least weekly.** A backup that lives only on the server is lost with the server.

  ```powershell
  .\deploy\pull-backup.ps1 -Server variant@myvariant.duckdns.org -KeyFile $HOME\.ssh\variant_ed25519 -Dest D:\variant-backups
  ```

  It checks the copy's checksum and warns if the newest backup on the server is more than 36 hours old.
- **Practise a restore once** before you depend on it (on a spare machine, or after the first deployment, before real
  users): on a server with a fresh stack,
  `deploy/restore.sh /path/to/variant-backup-....tar.age /path/to/variant-backup-key.txt` (copy the private key up only for
  this, then `shred -u` it). It asks nothing, replaces the database and the files with the backup's, and starts the stack.
  It refuses to touch a stack that already has users unless you add `--force`.

## 13. Day to day

| Task | Command (as `variant`, in `/opt/variant`) |
|---|---|
| Is everything running? | `docker compose -p variant -f deploy/docker-compose.prod.yml ps` |
| Logs of one service | `docker compose -p variant -f deploy/docker-compose.prod.yml logs --tail 100 api` (or `worker`, `renderer`, `caddy`, `postgres`) |
| Update to the newest code | `deploy/deploy.sh` (it makes a backup first) |
| Go to an exact version | `deploy/deploy.sh <commit id>` |
| Disk space | `df -h /` and `docker system df` (`docker image prune -f` frees old images) |

Logs contain ids, codes and timings only — never résumé, notes or posting text. Docker keeps only the last 3 × 10 MB per service.

Rolling back an update is `deploy/deploy.sh <the previous commit>`. Database upgrades only go forward: if an update
changed the database and you must undo it, restore the backup that `deploy.sh` made just before it.

**Do not add artificial load to keep the machine "busy".** Oracle only reclaims an Always Free machine that stays idle on
CPU, network *and* memory for 7 days; with the database, both Java programs and LibreOffice resident, memory alone keeps it
above the line.

## 14. When something goes wrong

| Symptom | Likely cause and fix |
|---|---|
| The site does not load at all | Ports 80/443 closed in Oracle's security list (step 3.4). Test from your computer: `Test-NetConnection <IP> -Port 443`. |
| Browser warns about the certificate | The host name does not point at the server yet (step 4), or port 80 is closed. Watch: `docker compose -p variant -f deploy/docker-compose.prod.yml logs -f caddy`. |
| Google says `redirect_uri_mismatch` | The URI in step 5.3 must be exactly `https://<APP_HOST>/api/auth/google/callback`. |
| `deploy.sh` says *REFUSING TO START ... ARM gate* | Run step 9. It also asks again after code changes in `engine/`, `renderer/` or `docker/`. |
| `deploy.sh` says a variable is not set | Fill it in `deploy/.env` (step 8). |
| Generation fails at once | `ANTHROPIC_API_KEY` is empty or has no credit: `docker compose ... logs worker`. |
| Everything is slow or containers restart | Out of memory: use `PROFILE=small` on a 4 GB machine, and look at `docker stats --no-stream`. |

## What is where

| File | What it is |
|---|---|
| `docker-compose.prod.yml` | the five services, networks and memory limits |
| `Caddyfile` | HTTPS, `/` = the web app, `/api/*` = the api |
| `.env.example` | every setting, documented |
| `profiles/standard.env`, `profiles/small.env` | memory sizes for a 12 GB or 4 GB machine |
| `install.sh`, `deploy.sh`, `arm-gate.sh` | setting up, updating, the ARM check |
| `backup.sh`, `restore.sh`, `pull-backup.ps1` | backups |
| `site/` | the web app's files, served at `/` (a placeholder until the web app is built; override the folder with `SITE_DIR`) |
| `docker-compose.test.yml` | tests only; never used on a server |

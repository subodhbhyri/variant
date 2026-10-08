# A throwaway "server" for the backup and restore test (scripts/deploy-test.ps1): bash, GNU coreutils, git, age, and the
# Docker CLI with compose, talking to the host's Docker through its socket. deploy/backup.sh and restore.sh run in it
# exactly as they run on the real server.
FROM docker:27-cli
RUN apk add --no-cache bash coreutils git age util-linux

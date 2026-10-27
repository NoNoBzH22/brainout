#!/bin/sh
# Starts the offline BrainOut open world (Free Play) server.
#
# PORT            tcp port; udp uses PORT+1 and http PORT+2      (default: 36555)
# BOTS            player-like bots roaming the world             (default: 8)
# BOT_DIFFICULTY  easy, normal, hard, nightmare or mix           (default: mix)
# BOT_DEBUG       set to anything to log what every bot is doing
# REGISTRATION    open: logging in with a new name creates the account; closed: no new accounts
# ALLOWED_ACCOUNTS  comma separated names allowed to create an account (default: anyone)
# ADMINS          comma separated account names with admin rights (console commands)
# DATA_DIR        where accounts and profiles are kept           (default: /data, mount a volume)
# JAVA_OPTS       extra JVM options                              (default: -Xmx1g)
# Extra container arguments are passed to the server as is.

set -e

PORT="${PORT:-36555}"
export FREEPLAY_BOTS="${BOTS:-8}"
export BOT_DIFFICULTY="${BOT_DIFFICULTY:-mix}"

echo "BrainOut open world: ports=$PORT,$((PORT + 1)),$((PORT + 2)) bots=$FREEPLAY_BOTS difficulty=$BOT_DIFFICULTY"

exec java \
    -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC \
    -Djava.net.preferIPv4Stack=true \
    ${JAVA_OPTS:--Xmx1g} \
    -jar /brainout/server/brainout-server.jar \
    no "$PORT,$((PORT + 1)),$((PORT + 2))" \
    --offline --mode free --settings server-free.json --maps freeplay-maps.shuffle \
    "$@"

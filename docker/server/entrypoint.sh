#!/bin/sh
# Starts one offline BrainOut game server.
#
# MODE      game mode: free (open world), normal, deathmatch, domination, ctf,
#           assault, foxhunt, gungame, zombie, duel        (default: normal)
# PORT      tcp port; udp uses PORT+1 and http PORT+2    (default: 36555)
# BOTS      bots kept in the match (ignored by free mode)  (default: 8)
# SETTINGS  settings file, defaults to the one matching MODE
# MAPS      map set file, defaults to the one matching MODE
# JAVA_OPTS extra JVM options                              (default: -Xmx512m)
# Extra container arguments are passed to the server as is.

set -e

MODE="${MODE:-normal}"
PORT="${PORT:-36555}"
BOTS="${BOTS:-8}"

case "$MODE" in
    free)
        : "${SETTINGS:=server-free.json}"
        : "${MAPS:=freeplay-maps.shuffle}"
        ;;
    duel)
        : "${SETTINGS:=server-duel.json}"
        : "${MAPS:=maps-set.json}"
        ;;
    zombie)
        : "${SETTINGS:=server-settings.json}"
        : "${MAPS:=zombie-set.json}"
        ;;
    *)
        : "${SETTINGS:=server-settings.json}"
        : "${MAPS:=maps-set.json}"
        ;;
esac

# Read by BrainOutServer from the environment
if [ -z "$server_settings" ]; then
    export server_settings="{\"bots\":$BOTS}"
fi

echo "BrainOut server: mode=$MODE ports=$PORT,$((PORT + 1)),$((PORT + 2)) settings=$SETTINGS maps=$MAPS bots=$BOTS"

exec java \
    -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC \
    -Djava.net.preferIPv4Stack=true \
    ${JAVA_OPTS:--Xmx512m} \
    -jar /brainout/server/brainout-server.jar \
    no "$PORT,$((PORT + 1)),$((PORT + 2))" \
    --offline --mode "$MODE" --settings "$SETTINGS" --maps "$MAPS" \
    "$@"

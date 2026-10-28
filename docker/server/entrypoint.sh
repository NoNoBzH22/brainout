#!/bin/bash
# Starts the offline BrainOut servers: one game per mode, each on its own ports.
#
# MODES           comma separated modes to run           (default: lobby,free,deathmatch,ctf)
#                 lobby = where players arrive and pick a mode, free = open world,
#                 deathmatch = everyone for themselves, ctf = capture the flag
# PORT            base tcp port; each mode has its own: free PORT, deathmatch PORT+10,
#                 ctf PORT+20, lobby PORT+30 (udp uses port+1 and http port+2)
# BOTS            player-like bots roaming the open world            (default: 8)
# BOT_DIFFICULTY  open world bots: adaptive (matches the players around), easy, normal, hard,
#                 nightmare or mix                                   (default: adaptive)
# MATCH_BOTS      bots filling deathmatch and capture the flag games (default: 8)
# MATCH_BOT_DIFFICULTY  same choices, adaptive follows the players' level and current game
# BOT_DAMAGE      multiplier of the damage bots deal to players, 0.1 to 5   (default: 1)
# BOT_ACCURACY    multiplier of bot accuracy, 0.1 to 5                       (default: 1)
#                 Admins can change the bots live with /bots, /difficulte, /botdegats, /botprecision.
# XP_MULTIPLIER   multiplier of the experience players earn, 0.1 to 20       (default: 1)
# BOT_DEBUG       set to anything to log what every bot is doing
# REGISTRATION    open: logging in with a new name creates the account; closed: no new accounts
# ALLOWED_ACCOUNTS  comma separated names allowed to create an account (default: anyone)
# ADMINS          comma separated account names with admin rights (console commands)
# DATA_DIR        where accounts and profiles are kept           (default: /data, mount a volume)
# JAVA_OPTS       extra JVM options for every mode   (default: -Xmx1g open world, -Xmx512m matches)

set -e

PORT="${PORT:-36555}"
MODES="${MODES:-lobby,free,deathmatch,ctf}"
export FREEPLAY_BOTS="${BOTS:-8}"
export BOT_DIFFICULTY="${BOT_DIFFICULTY:-adaptive}"
MATCH_BOTS="${MATCH_BOTS:-8}"

DATA="${DATA_DIR:-/data}"
if ! touch "$DATA/.write-test" 2>/dev/null; then
    echo "ERROR: $DATA is not writable by user $(id -u). Give it to the server on the host:" >&2
    echo "  chown -R $(id -u):$(id -g) <the host folder mounted on $DATA>" >&2
    exit 1
fi
rm -f "$DATA/.write-test"

start_mode() {
    local mode="$1" port settings maps memory bots

    # fixed offsets: the game finds each mode at the same place whatever MODES holds
    case "$mode" in
        free)
            port=$PORT; settings=server-free.json; maps=freeplay-maps.shuffle; memory=-Xmx1g; bots=0 ;;
        deathmatch)
            port=$((PORT + 10)); settings=server-settings.json; maps=maps-deathmatch.json; memory=-Xmx512m; bots="$MATCH_BOTS" ;;
        ctf)
            port=$((PORT + 20)); settings=server-settings.json; maps=maps-ctf.json; memory=-Xmx512m; bots="$MATCH_BOTS" ;;
        lobby)
            port=$((PORT + 30)); settings=server-lobby.json; maps=maps/lobby.map; memory=-Xmx384m; bots=0 ;;
        *)
            echo "ERROR: unknown mode $mode (lobby, free, deathmatch, ctf)" >&2
            exit 1 ;;
    esac

    echo "BrainOut $mode: ports $port,$((port + 1)),$((port + 2))"

    # match bots are read from server_settings; the open world uses FREEPLAY_BOTS
    server_settings="{\"bots\":$bots}" java \
        -XX:-OmitStackTraceInFastThrow -XX:+UseG1GC \
        -Djava.net.preferIPv4Stack=true \
        ${JAVA_OPTS:-$memory} \
        -jar /brainout/server/brainout-server.jar \
        no "$port,$((port + 1)),$((port + 2))" \
        --offline --mode "$mode" --settings "$settings" --maps "$maps" \
        2>&1 | sed -u "s/^/[$mode] /" &
}

echo "Open world bots: $FREEPLAY_BOTS ($BOT_DIFFICULTY), match bots: $MATCH_BOTS (${MATCH_BOT_DIFFICULTY:-adaptive})"

IFS=',' read -ra mode_list <<< "$MODES"
for mode in "${mode_list[@]}"; do
    start_mode "$(echo "$mode" | tr -d ' ')"
done

# if any game stops, stop the container so it restarts as a whole
trap 'kill $(jobs -p) 2>/dev/null' TERM INT
wait -n
echo "A game server stopped, shutting down" >&2
kill $(jobs -p) 2>/dev/null
wait
exit 1

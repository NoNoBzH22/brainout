# BrainOut offline game server.
# Build context: the repository root, after
#   (cd scripts && python3 make_data.py server)   -> bin/server/packages
#   ./gradlew :server:dist                         -> bin/server/brainout-server.jar
FROM eclipse-temurin:17-jre

WORKDIR /brainout/server

COPY bin/server/ /brainout/server/
COPY docker/server/entrypoint.sh /usr/local/bin/brainout-server

# the server runs as an unprivileged user: give it the game folder and the data volume.
# A host folder mounted on /data must belong to uid 10001 (chown -R 10001:10001 <folder>).
RUN chmod +x /usr/local/bin/brainout-server \
    && rm -f /brainout/server/*.bat /brainout/server/*.sh \
    && groupadd --system --gid 10001 brainout \
    && useradd --system --uid 10001 --gid brainout --home-dir /brainout/server --no-create-home --shell /usr/sbin/nologin brainout \
    && mkdir -p /data \
    && chown -R brainout:brainout /brainout/server /data

# accounts and player profiles
ENV DATA_DIR=/data
VOLUME /data

USER brainout

# open world, deathmatch, capture the flag
EXPOSE 36555/tcp 36556/udp 36557/tcp 36565/tcp 36566/udp 36567/tcp 36575/tcp 36576/udp 36577/tcp

ENTRYPOINT ["brainout-server"]

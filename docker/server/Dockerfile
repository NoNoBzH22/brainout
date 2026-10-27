# BrainOut offline game server.
# Build context: the repository root, after
#   (cd scripts && python3 make_data.py server)   -> bin/server/packages
#   ./gradlew :server:dist                         -> bin/server/brainout-server.jar
FROM eclipse-temurin:17-jre

WORKDIR /brainout/server

COPY bin/server/ /brainout/server/
COPY docker/server/entrypoint.sh /usr/local/bin/brainout-server
RUN chmod +x /usr/local/bin/brainout-server \
    && rm -f /brainout/server/*.bat /brainout/server/*.sh

# accounts and player profiles
ENV DATA_DIR=/data
VOLUME /data

EXPOSE 36555/tcp 36556/udp 36557/tcp

ENTRYPOINT ["brainout-server"]

ARG RUNTIME_IMAGE
FROM ${RUNTIME_IMAGE}
USER root
RUN apt-get update && apt-get install --no-install-recommends -y git ca-certificates python3 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/undertow
COPY target/undertow.jar /opt/undertow/undertow.jar
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/opt/undertow/undertow.jar"]

# 公开基础镜像、JDK 21，按源码构建，不使用开发电脑的 target。
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21-alpine
ARG RUNTIME_IMAGE=ibm-semeru-runtimes:open-21-jre-jammy
FROM ${MAVEN_IMAGE} AS builder
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Shanghai
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl fontconfig tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cloud \
    && useradd --uid 10001 --gid cloud --create-home cloud \
    && mkdir -p /app/data/workspaces /app/data/course-materials /app/data/logs /app/upload-staging \
    && chown -R cloud:cloud /app \
    && chmod 700 /app/upload-staging
WORKDIR /app
COPY --from=builder --chown=cloud:cloud /build/target/tongligong-student-cloud-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=8s --start-period=120s --retries=3 \
  CMD curl --fail --silent --max-time 6 http://127.0.0.1:8080/internal/ready > /dev/null || exit 1
# exec 形式正确接收停止信号；内存限制由 Compose 的 JAVA_TOOL_OPTIONS 设置。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

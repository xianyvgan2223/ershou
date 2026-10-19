# Sentinel Dashboard 1.8.8（官方镜像 docker.io/bladex/sentinel-dashboard 在本地被 403 拦截，改为自建）
# 构建上下文：ershou-cloud/docker/sentinel/
#   sentinel-dashboard.jar 由 scripts/cloud-up.ps1 自动从 GitHub Release 下载（已下载 5.3MB）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY sentinel-dashboard.jar /app/sentinel-dashboard.jar
EXPOSE 8858
ENV TZ=Asia/Shanghai
ENTRYPOINT ["sh", "-c", "java -Dserver.port=8858 -Dcsp.sentinel.dashboard.server=localhost:8858 -Dproject.name=sentinel-dashboard -jar /app/sentinel-dashboard.jar"]

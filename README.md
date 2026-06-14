# 員工座位安排系統

三層式範例專案：Nginx 提供 Vue 3 靜態檔並反向代理 `/api`，Spring Boot 3 呼叫 PostgreSQL Stored Procedure，資料一致性由 DB 約束與交易保證。

## 環境需求

- Docker Desktop / Docker Engine
- Docker Compose v2
- 本機 `80` port
- 本機跑測試，需要 Java 17
- 專案也可透過 Docker build

## 使用 Docker 啟動

專案預設用 Docker Compose 啟動三個服務：

- `db`：PostgreSQL 16，第一次建立 volume 時會自動執行 `DB/001_init.sql`
- `app`：Spring Boot API，連線到 `db:5432`
- `nginx`：提供 Vue 靜態檔，並把 `/api/*` 反向代理到 Spring Boot

在專案根目錄執行：

```bash
docker compose up -d --build
```

啟動完成後檢查容器狀態：

```bash
docker compose ps
```

## 開啟系統

瀏覽器開啟：

```text
http://localhost
```

API 確認後端和資料庫是否正常：

```bash
curl -f http://localhost/api/seats
```

## 執行測試

後端測試使用 Gradle：

```bash
./gradlew test
```

測試使用 Testcontainers，執行時需要 Docker 正在運作，且目前使用者有權限連線 Docker socket。
前端 production build 可用：

```bash
cd frontend
npm install --no-package-lock
npm run build
```

## 設計決策

- DB 存取集中在 Stored Procedure，Java Repository 使用 `SimpleJdbcCall`。
- `SEAT_CHANGE_LOG` 用於稽核，也作為 `snapshotVersion` 來源。

## API

- `GET /api/seats`
- `GET /api/employees`
- `PUT /api/seats/assignments`

統一回應：

```json
{ "code": "OK", "message": "OK", "data": {} }
```

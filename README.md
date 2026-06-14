# 員工座位安排系統

三層式範例專案：Nginx 提供 Vue 3 靜態檔並反向代理 `/api`，Spring Boot 3 呼叫 PostgreSQL Stored Procedure，資料一致性由 DB 約束與交易保證。

## 啟動

```bash
docker compose up -d --build
curl -f http://localhost/api/seats
```

瀏覽器開啟 `http://localhost`。

## 本機測試

本機目前若尚未安裝 Java 17+，可使用專案提供的 Docker 型 `./gradlew`：

```bash
chmod +x ./gradlew
./gradlew test
```

## 設計決策

- DB 存取集中在 Stored Procedure，Java Repository 使用 `SimpleJdbcCall`。
- `SEAT_CHANGE_LOG` 用於稽核，也作為 `snapshotVersion` 來源。
- 清除本來沒有座位的員工回 400，因為正常前端流程不會產生該操作。
- 不用 message queue：這是低併發內部工具，同步交易語意由 PostgreSQL 保證。
- 不用 Redis：座位資料量極小，快取會增加一致性風險。
- 不做登入授權：題目未要求；真實系統可接 Spring Security 與企業 SSO。

## API

- `GET /api/seats`
- `GET /api/employees`
- `PUT /api/seats/assignments`

統一回應：

```json
{ "code": "OK", "message": "OK", "data": {} }
```

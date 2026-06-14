-- PostgreSQL 初始化腳本。
-- Docker Compose 啟動 db 容器時，會自動執行 /docker-entrypoint-initdb.d 底下的 .sql。
--
-- 對 JPA/Hibernate 背景的工程師來說，這裡等同於 schema.sql + seed data + named query/SP。
-- 本專案刻意把資料一致性放在 DB constraint 與 Stored Procedure，而不是 Hibernate mapping。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 初始化時先清除舊表，方便重建乾淨環境。
-- 注意正式環境不會這樣 drop table；這是題目要求可重複初始化的開發腳本。
DROP TABLE IF EXISTS seat_change_log;
DROP TABLE IF EXISTS employee;
DROP TABLE IF EXISTS seating_chart;

-- 座位主檔。floor_seat_seq 是題目指定的座位序號，也作為 API 的座位 id。
CREATE TABLE seating_chart (
  floor_seat_seq INT PRIMARY KEY,
  floor_no INT NOT NULL,
  seat_no INT NOT NULL,
  -- 同一樓層的同一座號不能重複。
  CONSTRAINT uq_floor_seat UNIQUE (floor_no, seat_no)
);

-- 員工主檔。
-- 這裡沒有 Hibernate Entity 對應；Java 端不直接 update table，而是呼叫 SP。
CREATE TABLE employee (
  emp_id CHAR(5) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(255) NOT NULL,
  -- NULL 代表目前沒有座位。
  floor_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  -- DB 層保證員編格式，即使應用層 validation 漏掉也不能寫入壞資料。
  CONSTRAINT ck_emp_id CHECK (emp_id ~ '^[0-9]{5}$'),
  CONSTRAINT ck_email CHECK (email ~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$'),
  CONSTRAINT ck_name_no_control CHECK (name !~ '[[:cntrl:]]'),
  -- 一個座位最多只能被一位員工佔用。
  -- DEFERRABLE 讓「互換座位」可在同一交易結束時才檢查唯一性。
  CONSTRAINT uq_employee_floor_seat UNIQUE (floor_seat_seq) DEFERRABLE INITIALLY DEFERRED
);

-- 異動紀錄表。
-- 除了稽核，也用 max(log_id) 當作 snapshotVersion 來源。
CREATE TABLE seat_change_log (
  log_id BIGSERIAL PRIMARY KEY,
  emp_id CHAR(5) NOT NULL REFERENCES employee(emp_id),
  from_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  to_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  batch_id UUID NOT NULL
);

-- 建立 4 樓 x 每樓 4 個座位，共 16 筆。
INSERT INTO seating_chart(floor_seat_seq, floor_no, seat_no)
SELECT ((floor_no - 1) * 4) + seat_no, floor_no, seat_no
FROM generate_series(1, 4) AS floor_no
CROSS JOIN generate_series(1, 4) AS seat_no;

-- 題目指定的初始座位資料，以及幾位無座位員工供前端下拉選單使用。
INSERT INTO employee(emp_id, name, email, floor_seat_seq) VALUES
('12006', '王小明', '12006@example.com', 3),
('16142', '林佳蓉', '16142@example.com', 7),
('13040', '陳柏翰', '13040@example.com', 9),
('17081', '張雅婷', '17081@example.com', 10),
('11221', '李宗翰', '11221@example.com', 12),
('16722', '黃怡君', '16722@example.com', 15),
('10001', '吳承恩', '10001@example.com', NULL),
('10002', '周品妤', '10002@example.com', NULL),
('10003', '蔡明哲', '10003@example.com', NULL),
('10004', '鄭宇庭', '10004@example.com', NULL);

-- 目前版本號。沒有異動紀錄時為 0；每次成功異動都會新增 log，所以版本會遞增。
CREATE OR REPLACE FUNCTION sp_snapshot_version()
RETURNS TEXT
LANGUAGE sql
AS $$
  SELECT COALESCE(MAX(log_id), 0)::TEXT FROM seat_change_log
$$;

-- 查詢座位盤。
-- 回傳 JSON 字串，Java Repository 再用 Jackson 轉成 SeatSnapshotResponse。
CREATE OR REPLACE FUNCTION sp_get_seats()
RETURNS TEXT
LANGUAGE sql
AS $$
  SELECT jsonb_build_object(
    'snapshotVersion', sp_snapshot_version(),
    'seats', COALESCE(jsonb_agg(jsonb_build_object(
      'floorNo', sc.floor_no,
      'seatNo', sc.seat_no,
      'floorSeatSeq', sc.floor_seat_seq,
      'occupiedBy', e.emp_id,
      'empName', e.name
    ) ORDER BY sc.floor_no, sc.seat_no), '[]'::jsonb)
  )::TEXT
  FROM seating_chart sc
  LEFT JOIN employee e ON e.floor_seat_seq = sc.floor_seat_seq
$$;

-- 查詢員工下拉選單。
CREATE OR REPLACE FUNCTION sp_get_employees()
RETURNS TEXT
LANGUAGE sql
AS $$
  SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'empId', emp_id,
    'name', name,
    'currentSeatSeq', floor_seat_seq
  ) ORDER BY emp_id), '[]'::jsonb)::TEXT
  FROM employee
$$;

-- 批次指派座位的核心 Stored Procedure。
--
-- JPA/Hibernate 常見做法是在 Service 裡 load entity 後 set field，再交給 flush。
-- 本專案因題目要求，將核心一致性放在 DB 內：
--   1. 檢查 snapshotVersion
--   2. 驗證批次資料
--   3. 鎖住相關座位與員工
--   4. 檢查座位衝突
--   5. 更新 employee.floor_seat_seq
--   6. 寫入 seat_change_log
CREATE OR REPLACE FUNCTION sp_assign_seats(p_snapshot_version TEXT, p_changes JSONB)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
  v_current_version TEXT;
  v_batch_id UUID := gen_random_uuid();
  v_count INT;
BEGIN
  IF p_changes IS NULL OR jsonb_typeof(p_changes) <> 'array' OR jsonb_array_length(p_changes) = 0 THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: changes must be a non-empty array';
  END IF;

  -- 樂觀比對：前端送出的版本必須等於 DB 目前版本。
  SELECT sp_snapshot_version() INTO v_current_version;
  IF v_current_version <> p_snapshot_version THEN
    RAISE EXCEPTION 'CONFLICT_STALE_SNAPSHOT: expected %, got %', p_snapshot_version, v_current_version;
  END IF;

  -- 暫存本批異動。ON COMMIT DROP 代表交易結束自動移除。
  CREATE TEMP TABLE tmp_changes(
    emp_id CHAR(5) PRIMARY KEY,
    to_seat_seq INT NULL,
    from_seat_seq INT NULL
  ) ON COMMIT DROP;

  INSERT INTO tmp_changes(emp_id, to_seat_seq)
  SELECT s.emp_id, s.to_seat_seq
  -- JSON key 大小寫要用雙引號保留，否則 PostgreSQL 會折成小寫。
  FROM jsonb_to_recordset(p_changes) AS x("empId" TEXT, "toSeatSeq" INT)
  CROSS JOIN LATERAL (SELECT x."empId"::CHAR(5) AS emp_id, x."toSeatSeq" AS to_seat_seq) s;

  -- 若 temp table primary key 因重複 emp_id 導致插入筆數不一致，就拒絕整批。
  GET DIAGNOSTICS v_count = ROW_COUNT;
  IF v_count <> jsonb_array_length(p_changes) THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: duplicate employees are not allowed';
  END IF;

  IF EXISTS (SELECT 1 FROM tmp_changes WHERE emp_id !~ '^[0-9]{5}$') THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: invalid employee id';
  END IF;

  IF EXISTS (
    SELECT to_seat_seq FROM tmp_changes WHERE to_seat_seq IS NOT NULL GROUP BY to_seat_seq HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: duplicate target seats are not allowed';
  END IF;

  IF EXISTS (
    SELECT 1 FROM tmp_changes c LEFT JOIN employee e ON e.emp_id = c.emp_id WHERE e.emp_id IS NULL
  ) THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: employee does not exist';
  END IF;

  IF EXISTS (
    SELECT 1 FROM tmp_changes c LEFT JOIN seating_chart s ON s.floor_seat_seq = c.to_seat_seq
    WHERE c.to_seat_seq IS NOT NULL AND s.floor_seat_seq IS NULL
  ) THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: seat does not exist';
  END IF;

  -- 鎖住本批會碰到的座位列。排序鎖定可降低死鎖機率。
  PERFORM 1
  FROM seating_chart
  WHERE floor_seat_seq IN (
    SELECT to_seat_seq FROM tmp_changes WHERE to_seat_seq IS NOT NULL
    UNION
    SELECT e.floor_seat_seq FROM employee e JOIN tmp_changes c ON c.emp_id = e.emp_id WHERE e.floor_seat_seq IS NOT NULL
  )
  ORDER BY floor_seat_seq
  FOR UPDATE;

  -- 同時鎖住本批員工，避免其他交易同時改同一員工的座位。
  PERFORM 1
  FROM employee
  WHERE emp_id IN (SELECT emp_id FROM tmp_changes)
  ORDER BY emp_id
  FOR UPDATE;

  -- 保留異動前座位，後面寫 log 會用到。
  UPDATE tmp_changes c
  SET from_seat_seq = e.floor_seat_seq
  FROM employee e
  WHERE e.emp_id = c.emp_id;

  IF EXISTS (SELECT 1 FROM tmp_changes WHERE to_seat_seq IS NULL AND from_seat_seq IS NULL) THEN
    RAISE EXCEPTION 'VALIDATION_ERROR: cannot clear an employee without a seat';
  END IF;

  -- 目標座位若被「本批以外」的人佔用，就拒絕。
  -- 本批內互換座位，例如 A->B 的位、B->A 的位，允許通過。
  IF EXISTS (
    SELECT 1
    FROM tmp_changes c
    JOIN employee occupant ON occupant.floor_seat_seq = c.to_seat_seq
    LEFT JOIN tmp_changes moving ON moving.emp_id = occupant.emp_id
    WHERE c.to_seat_seq IS NOT NULL AND moving.emp_id IS NULL
  ) THEN
    RAISE EXCEPTION 'CONFLICT_SEAT_TAKEN: target seat is occupied outside this batch';
  END IF;

  -- 延後唯一約束檢查，支援同批互換與環狀換位。
  SET CONSTRAINTS uq_employee_floor_seat DEFERRED;

  -- 實際更新座位。此時仍在同一交易內。
  UPDATE employee e
  SET floor_seat_seq = c.to_seat_seq
  FROM tmp_changes c
  WHERE e.emp_id = c.emp_id;

  -- 每筆真正有變化的異動都寫 log。若 from/to 相同，不產生 no-op log。
  INSERT INTO seat_change_log(emp_id, from_seat_seq, to_seat_seq, batch_id)
  SELECT emp_id, from_seat_seq, to_seat_seq, v_batch_id
  FROM tmp_changes
  WHERE from_seat_seq IS DISTINCT FROM to_seat_seq;

  -- 回傳最新座位盤，讓前端不用再多打一支 GET。
  RETURN sp_get_seats();
END;
$$;

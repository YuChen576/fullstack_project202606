CREATE EXTENSION IF NOT EXISTS pgcrypto;

DROP TABLE IF EXISTS seat_change_log;
DROP TABLE IF EXISTS employee;
DROP TABLE IF EXISTS seating_chart;

CREATE TABLE seating_chart (
  floor_seat_seq INT PRIMARY KEY,
  floor_no INT NOT NULL,
  seat_no INT NOT NULL,
  CONSTRAINT uq_floor_seat UNIQUE (floor_no, seat_no)
);

CREATE TABLE employee (
  emp_id CHAR(5) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(255) NOT NULL,
  floor_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  CONSTRAINT ck_emp_id CHECK (emp_id ~ '^[0-9]{5}$'),
  CONSTRAINT ck_email CHECK (email ~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$'),
  CONSTRAINT ck_name_no_control CHECK (name !~ '[[:cntrl:]]'),
  -- DEFERRABLE 讓「互換座位」可在同一交易結束時才檢查唯一性。
  CONSTRAINT uq_employee_floor_seat UNIQUE (floor_seat_seq) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE seat_change_log (
  log_id BIGSERIAL PRIMARY KEY,
  emp_id CHAR(5) NOT NULL REFERENCES employee(emp_id),
  from_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  to_seat_seq INT NULL REFERENCES seating_chart(floor_seat_seq),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  batch_id UUID NOT NULL
);

INSERT INTO seating_chart(floor_seat_seq, floor_no, seat_no)
SELECT ((floor_no - 1) * 4) + seat_no, floor_no, seat_no
FROM generate_series(1, 4) AS floor_no
CROSS JOIN generate_series(1, 4) AS seat_no;

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

-- 以異動紀錄流水號作為座位快照版本。
CREATE OR REPLACE FUNCTION sp_snapshot_version()
RETURNS TEXT
LANGUAGE sql
AS $$
  SELECT COALESCE(MAX(log_id), 0)::TEXT FROM seat_change_log
$$;

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

CREATE OR REPLACE FUNCTION sp_get_seat_sequences()
RETURNS TEXT
LANGUAGE sql
AS $$
  SELECT COALESCE(jsonb_agg(floor_seat_seq ORDER BY floor_seat_seq), '[]'::jsonb)::TEXT
  FROM seating_chart
$$;

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

  -- 樂觀比對，避免覆蓋較新的座位狀態。
  SELECT sp_snapshot_version() INTO v_current_version;
  IF v_current_version <> p_snapshot_version THEN
    RAISE EXCEPTION 'CONFLICT_STALE_SNAPSHOT: expected %, got %', p_snapshot_version, v_current_version;
  END IF;

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

  -- 重複 emp_id 會被 temp table primary key 合併，需比對筆數。
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

  -- 鎖住本批員工，避免其他交易同時改同一員工座位。
  PERFORM 1
  FROM employee
  WHERE emp_id IN (SELECT emp_id FROM tmp_changes)
  ORDER BY emp_id
  FOR UPDATE;

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

  UPDATE employee e
  SET floor_seat_seq = c.to_seat_seq
  FROM tmp_changes c
  WHERE e.emp_id = c.emp_id;

  -- 不記錄 from/to 相同的 no-op。
  INSERT INTO seat_change_log(emp_id, from_seat_seq, to_seat_seq, batch_id)
  SELECT emp_id, from_seat_seq, to_seat_seq, v_batch_id
  FROM tmp_changes
  WHERE from_seat_seq IS DISTINCT FROM to_seat_seq;

  RETURN sp_get_seats();
END;
$$;

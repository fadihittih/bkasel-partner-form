// MySQL connection pool and table creation.
// All timestamps are stored as UTC DATETIME(3); conversion to Asia/Amman happens when reading.
import mysql from "mysql2/promise";
import type { Config } from "./config.js";

export type Pool = mysql.Pool;

export function createPool(cfg: Config["db"]): Pool {
  const common = {
    timezone: "Z" as const, // treat DATETIME values as UTC
    dateStrings: false,
    connectionLimit: 5,
    waitForConnections: true,
  };
  return "uri" in cfg
    ? mysql.createPool({ uri: cfg.uri, ...common })
    : mysql.createPool({ ...cfg, ...common });
}

// Record IDs are Health Connect's record IDs, so re-sending the same record updates it in place.
const TABLES = [
  `CREATE TABLE IF NOT EXISTS sleep_sessions (
     id            VARCHAR(64)  NOT NULL PRIMARY KEY,
     start_time    DATETIME(3)  NOT NULL,
     end_time      DATETIME(3)  NOT NULL,
     duration_min  INT          NOT NULL,
     title         VARCHAR(255) NULL,
     origin        VARCHAR(255) NOT NULL,
     last_modified DATETIME(3)  NOT NULL,
     INDEX idx_sleep_start (start_time)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,

  `CREATE TABLE IF NOT EXISTS sleep_stages (
     session_id  VARCHAR(64) NOT NULL,
     stage_type  VARCHAR(16) NOT NULL,
     stage_code  TINYINT     NOT NULL,
     start_time  DATETIME(3) NOT NULL,
     end_time    DATETIME(3) NOT NULL,
     PRIMARY KEY (session_id, start_time),
     CONSTRAINT fk_stage_session FOREIGN KEY (session_id)
       REFERENCES sleep_sessions(id) ON DELETE CASCADE
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,

  // High volume: one row per sample. record_id groups samples of one Health Connect record.
  `CREATE TABLE IF NOT EXISTS heart_rate_samples (
     record_id    VARCHAR(64)  NOT NULL,
     sample_time  DATETIME(3)  NOT NULL,
     bpm          SMALLINT     NOT NULL,
     origin       VARCHAR(255) NOT NULL,
     PRIMARY KEY (record_id, sample_time),
     INDEX idx_hr_time (sample_time)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,

  `CREATE TABLE IF NOT EXISTS steps (
     id            VARCHAR(64)  NOT NULL PRIMARY KEY,
     start_time    DATETIME(3)  NOT NULL,
     end_time      DATETIME(3)  NOT NULL,
     count         INT          NOT NULL,
     origin        VARCHAR(255) NOT NULL,
     last_modified DATETIME(3)  NOT NULL,
     INDEX idx_steps_start (start_time)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,

  `CREATE TABLE IF NOT EXISTS exercise_sessions (
     id            VARCHAR(64)  NOT NULL PRIMARY KEY,
     start_time    DATETIME(3)  NOT NULL,
     end_time      DATETIME(3)  NOT NULL,
     exercise_type INT          NOT NULL,
     title         VARCHAR(255) NULL,
     origin        VARCHAR(255) NOT NULL,
     last_modified DATETIME(3)  NOT NULL,
     INDEX idx_ex_start (start_time)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,

  // One row per accepted upload - counts only, for the status endpoint.
  `CREATE TABLE IF NOT EXISTS ingest_log (
     id          BIGINT AUTO_INCREMENT PRIMARY KEY,
     received_at DATETIME(3) NOT NULL,
     summary     VARCHAR(255) NOT NULL
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
];

/** Creates tables if they don't exist. Safe to run on every start. */
export async function migrate(pool: Pool): Promise<void> {
  for (const sql of TABLES) await pool.query(sql);
}

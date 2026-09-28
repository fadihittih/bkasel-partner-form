// Entry point: load config, prepare the database, start listening.
import { loadConfig } from "./config.js";
import { createPool, migrate } from "./db.js";
import { createApp } from "./app.js";

const cfg = loadConfig();
const pool = createPool(cfg.db);
await migrate(pool);

createApp(pool, cfg.apiToken).listen(cfg.port, () => {
  console.log(`health backend listening on port ${cfg.port}`);
});

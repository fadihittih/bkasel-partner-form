// Reads settings from environment variables and fails fast if something is missing.

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing environment variable ${name}`);
  return v;
}

export function loadConfig() {
  const apiToken = required("API_TOKEN");
  if (apiToken.length < 32) throw new Error("API_TOKEN must be at least 32 characters");

  const db = process.env.DATABASE_URL
    ? { uri: process.env.DATABASE_URL }
    : {
        host: process.env.DB_HOST ?? "localhost",
        port: Number(process.env.DB_PORT ?? 3306),
        user: required("DB_USER"),
        password: process.env.DB_PASSWORD ?? "",
        database: required("DB_NAME"),
      };

  return {
    apiToken,
    db,
    port: Number(process.env.PORT ?? 3000),
  };
}

export type Config = ReturnType<typeof loadConfig>;

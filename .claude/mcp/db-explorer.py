#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp[cli]", "psycopg2-binary"]
# ///
"""
Enterprise Project — DB Explorer MCP Server

Schema introspection is sourced from Flyway migration SQL files (no live
connection needed).  Live queries require PostgreSQL env vars and only
accept SELECT statements.

Tools
─────
  list_tables()         → table names across all migrations
  describe_table(name)  → columns, constraints, indexes for one table
  list_migrations()     → migration files with version, description, tables
  run_query(sql)        → SELECT-only query against PostgreSQL

PostgreSQL env vars (for run_query)
────────────────────────────────────
  DATABASE_URL=postgres://user:pass@host:port/db
  — or individually —
  DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD
"""

import os
import re
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP

# ── paths ─────────────────────────────────────────────────────────────────────

# Script lives at .claude/mcp/db-explorer.py → 3 parents up = project root
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
MIGRATIONS_DIR = _PROJECT_ROOT / "src/main/resources/db/migration"

server = FastMCP("enterprise-db-explorer")

# ── SQL helpers ───────────────────────────────────────────────────────────────

def _strip_comments(sql: str) -> str:
    sql = re.sub(r"--[^\n]*", "", sql)
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.DOTALL)
    return sql


def _parse_columns(body: str) -> list[dict]:
    cols: list[dict] = []
    for line in re.split(r",\s*\n", body):
        line = line.strip().rstrip(",").strip()
        if not line or re.match(
            r"(CONSTRAINT|PRIMARY\s+KEY|UNIQUE|CHECK|FOREIGN\s+KEY)", line, re.IGNORECASE
        ):
            continue
        m = re.match(r"(\w+)\s+(\S+)(.*)", line, re.DOTALL)
        if not m:
            continue
        rest = m.group(3)
        col: dict[str, Any] = {"name": m.group(1), "type": m.group(2)}
        col["nullable"] = "NOT NULL" not in rest.upper()
        if dm := re.search(r"DEFAULT\s+(\S+)", rest, re.IGNORECASE):
            col["default"] = dm.group(1).rstrip(",")
        if "PRIMARY KEY" in rest.upper():
            col["primary_key"] = True
        cols.append(col)
    return cols


def _parse_table_constraints(body: str) -> list[dict]:
    constraints: list[dict] = []
    for line in re.split(r",\s*\n", body):
        line = line.strip()
        if m := re.match(
            r"(?:CONSTRAINT\s+(\w+)\s+)?PRIMARY\s+KEY\s*\(([^)]+)\)", line, re.IGNORECASE
        ):
            constraints.append({
                "type": "PRIMARY KEY",
                "columns": [c.strip() for c in m.group(2).split(",")],
            })
        elif m := re.match(
            r"(?:CONSTRAINT\s+(\w+)\s+)?UNIQUE\s*\(([^)]+)\)", line, re.IGNORECASE
        ):
            constraints.append({
                "type": "UNIQUE",
                "name": m.group(1),
                "columns": [c.strip() for c in m.group(2).split(",")],
            })
        elif m := re.match(
            r"(?:CONSTRAINT\s+(\w+)\s+)?FOREIGN\s+KEY\s*\(([^)]+)\)"
            r"\s+REFERENCES\s+(\w+)\s*\(([^)]+)\)(?:\s+ON\s+DELETE\s+(\w+(?:\s+\w+)?))?",
            line, re.IGNORECASE,
        ):
            constraints.append({
                "type": "FOREIGN KEY",
                "name": m.group(1),
                "columns": [c.strip() for c in m.group(2).split(",")],
                "references": m.group(3),
                "ref_columns": [c.strip() for c in m.group(4).split(",")],
                "on_delete": m.group(5).upper() if m.group(5) else None,
            })
    return [c for c in constraints if c]


def _load_schema() -> dict[str, dict]:
    """Parse all V*.sql files; return dict keyed by lowercase table name."""
    tables: dict[str, dict] = {}

    for path in sorted(MIGRATIONS_DIR.glob("V*.sql")):
        sql = _strip_comments(path.read_text())

        for m in re.finditer(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.+?)\)\s*;",
            sql, re.IGNORECASE | re.DOTALL,
        ):
            name = m.group(1).lower()
            body = m.group(2)
            tables[name] = {
                "source_file": path.name,
                "columns": _parse_columns(body),
                "constraints": _parse_table_constraints(body),
                "indexes": [],
            }

        for m in re.finditer(
            r"CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)"
            r"\s+ON\s+(\w+)\s*\(([^)]+)\)(?:\s+WHERE\s+(.+?))?(?=\s*;)",
            sql, re.IGNORECASE | re.DOTALL,
        ):
            tname = m.group(3).lower()
            if tname in tables:
                tables[tname]["indexes"].append({
                    "name": m.group(2),
                    "unique": bool(m.group(1)),
                    "columns": [c.strip() for c in m.group(4).split(",")],
                    "where": m.group(5).strip() if m.group(5) else None,
                })

    return tables


# ── tools ─────────────────────────────────────────────────────────────────────

@server.tool()
def list_tables() -> list[str]:
    """List all tables defined across all Flyway migration SQL files."""
    return sorted(_load_schema().keys())


@server.tool()
def describe_table(name: str) -> dict:
    """
    Return columns, constraints, and indexes for a table.

    Args:
        name: table name (case-insensitive).
    """
    schema = _load_schema()
    row = schema.get(name.lower())
    if row is None:
        return {"error": f"Table '{name}' not found.", "known_tables": sorted(schema)}
    return row


@server.tool()
def list_migrations() -> list[dict]:
    """List Flyway migration files with version, description, and tables they create/alter."""
    result = []
    for path in sorted(MIGRATIONS_DIR.glob("V*.sql")):
        m = re.match(r"V(\d+(?:[._]\d+)?)__(.+)\.sql", path.name)
        version = m.group(1) if m else "?"
        description = m.group(2).replace("_", " ") if m else path.stem

        sql = _strip_comments(path.read_text())
        tables_created = re.findall(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", sql, re.IGNORECASE
        )
        indexes_created = re.findall(
            r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", sql, re.IGNORECASE
        )
        result.append({
            "version": version,
            "description": description,
            "file": path.name,
            "tables_created": tables_created,
            "indexes_created": indexes_created,
        })
    return result


@server.tool()
def run_query(sql: str) -> list[dict] | dict:
    """
    Execute a read-only SELECT query against PostgreSQL and return up to 200 rows.

    Only works with a live PostgreSQL instance (QA or prod).
    H2 (local dev) is not supported — use the H2 console at
    http://localhost:8080/enterprise/h2-console/ instead.

    Args:
        sql: A SELECT statement.

    Required env vars (pick one form):
        DATABASE_URL=postgres://user:pass@host:port/db
        — or individually —
        DB_HOST, DB_PORT (default 5432), DB_NAME, DB_USERNAME, DB_PASSWORD
    """
    if not re.match(r"\s*SELECT\b", sql, re.IGNORECASE):
        return {"error": "Only SELECT statements are permitted."}

    try:
        import psycopg2
        import psycopg2.extras
    except ImportError:
        return {"error": "psycopg2 is not installed in this environment."}

    database_url = os.getenv("DATABASE_URL")
    try:
        conn = (
            psycopg2.connect(database_url)
            if database_url
            else psycopg2.connect(
                host=os.getenv("DB_HOST", "localhost"),
                port=int(os.getenv("DB_PORT", "5432")),
                dbname=os.getenv("DB_NAME", "enterprise_dev"),
                user=os.getenv("DB_USERNAME", "postgres"),
                password=os.getenv("DB_PASSWORD", ""),
            )
        )
    except Exception as exc:
        return {"error": f"Could not connect to PostgreSQL: {exc}"}

    try:
        conn.set_session(readonly=True)
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql)
            return [dict(row) for row in cur.fetchmany(200)]
    except Exception as exc:
        return {"error": str(exc)}
    finally:
        conn.close()


if __name__ == "__main__":
    server.run()

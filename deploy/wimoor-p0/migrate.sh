#!/usr/bin/env bash
# 幂等数据库迁移执行器
# 用法: migrate.sh <mysql容器名> <库名> <迁移目录>
#   migrate.sh wm-p0-mysql db_admin deploy/wimoor-p0/mysql-init
# 规则:
#   1) 先确保 schema_migrations 表存在
#   2) 逐个 *.sql（按文件名排序）算 SHA256，与版本表比对
#   3) 已执行且 checksum 一致 -> SKIP（无变化不重复迁移）
#   4) 未执行 -> 先 mysqldump 备份该库，再执行，成功后记录版本+校验和
#   5) 已执行但 checksum 变化 -> 拒绝自动执行（防止不兼容变更），报错退出转人工
# 注意: 迁移失败不会自动回滚数据库（红线），保留备份与日志交人工处置。
set -euo pipefail

MYSQL_C="${1:-wm-p0-mysql}"
DB="${2:-db_admin}"
DIR="${3:-$(cd "$(dirname "$0")" && pwd)/mysql-init}"
MYSQL_PWD_ARG="-p123456"

echo "[migrate] 目标容器=$MYSQL_C 库=$DB 目录=$DIR"

# 容器就绪检查（有限轮询，不盲等）
for i in $(seq 1 30); do
  docker exec "$MYSQL_C" mysql -uroot "$MYSQL_PWD_ARG" -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done

# 1) 确保版本表
docker exec -i "$MYSQL_C" mysql -uroot "$MYSQL_PWD_ARG" --default-character-set=utf8mb4 "$DB" \
  < "$(dirname "$0")/mysql-init/00-schema-migrations.sql" 2>/dev/null || {
    echo "[migrate] 建版本表失败" >&2; exit 1;
  }

BK_DIR="$(dirname "$0")/backups"
mkdir -p "$BK_DIR"
backed_up=0
backup_db() {
  if [ "$backed_up" -eq 0 ]; then
    echo "[migrate] 迁移前备份 $DB ..."
    docker exec "$MYSQL_C" mysqldump -uroot "$MYSQL_PWD_ARG" --single-transaction \
      --routines --triggers "$DB" > "$BK_DIR/${DB}-pre-migrate-$(date +%Y%m%d-%H%M%S).sql"
    backed_up=1
  fi
}

# 2-5) 逐个迁移文件
for f in "$DIR"/*.sql; do
  [ -f "$f" ] || continue
  name="$(basename "$f")"
  case "$name" in 00-schema-migrations.sql) continue;; esac
  sum="$(sha256sum "$f" | awk '{print $1}')"
  existing="$(docker exec "$MYSQL_C" mysql -uroot "$MYSQL_PWD_ARG" -N \
    -e "SELECT checksum FROM $DB.schema_migrations WHERE version='$name'" 2>/dev/null || true)"
  if [ -n "$existing" ]; then
    if [ "$existing" = "$sum" ]; then
      echo "  SKIP $name（已执行，checksum 一致）"
    else
      echo "[migrate] 拒绝：$name 已执行但内容已变（checksum 不一致）。不兼容变更需人工处置。" >&2
      exit 1
    fi
    continue
  fi
  echo "  APPLY $name"
  backup_db
  if ! docker exec -i "$MYSQL_C" mysql -uroot "$MYSQL_PWD_ARG" --default-character-set=utf8mb4 "$DB" < "$f" 2>/dev/null; then
    echo "[migrate] 失败：$name。已保留备份 $BK_DIR/，不自动回滚数据库，转人工处置。" >&2
    exit 1
  fi
  docker exec "$MYSQL_C" mysql -uroot "$MYSQL_PWD_ARG" \
    -e "INSERT INTO $DB.schema_migrations(version,checksum) VALUES('$name','$sum')" 2>/dev/null
done
echo "[migrate] 完成：$DB"

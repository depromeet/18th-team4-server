#!/usr/bin/env bash
# MySQL 일일 백업 스크립트. 원본은 repo 의 infra/scripts/ 이고, 서버에서 cron 으로 실행한다:
#   30 4 * * * /opt/readum/bin/backup-mysql.sh >> /opt/readum/mysql-backup/backup.log 2>&1
# (04:30 — 트래픽 최저 시간대이면서 06:00 감상문 적재 배치와 겹치지 않게)
#
# 보관 정책: 로컬 7일. 서버가 통째로 사라지면 백업도 같이 사라지는 한계는
# 데이터 중요도가 낮은 현 단계에서 수용하기로 한 절충이다 — 운영 프로세스를
# 분리하는 시점에 외부 보관(S3 등)을 재검토한다.
set -euo pipefail

ENV_FILE="/opt/readum/.env"
BACKUP_DIR="/opt/readum/mysql-backup"
KEEP_DAYS=7

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"

# --single-transaction: 백업 중에도 서비스 쿼리를 막지 않는다 (InnoDB 스냅샷 읽기).
docker exec readum-mysql mysqldump \
  --single-transaction --routines --triggers \
  -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" \
  | gzip > "$BACKUP_DIR/readum-$STAMP.sql.gz"

find "$BACKUP_DIR" -name 'readum-*.sql.gz' -mtime +"$KEEP_DAYS" -delete

echo "$(date '+%F %T') 백업 완료: readum-$STAMP.sql.gz ($(du -h "$BACKUP_DIR/readum-$STAMP.sql.gz" | cut -f1))"

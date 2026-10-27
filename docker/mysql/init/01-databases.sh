#!/bin/bash
# 컨테이너를 처음 만들 때 한 번 실행된다.
# askflow_app: 서비스 DB(askflow)만 다룬다
# askflow_reader: 분석 대상 DB(olist)를 읽기만 한다 (F-003의 첫 번째 겹)
set -euo pipefail

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS askflow CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS olist CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'askflow_app'@'%' IDENTIFIED BY '${ASKFLOW_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON askflow.* TO 'askflow_app'@'%';

CREATE USER IF NOT EXISTS 'askflow_reader'@'%' IDENTIFIED BY '${ASKFLOW_READER_PASSWORD}';
GRANT SELECT, SHOW VIEW ON olist.* TO 'askflow_reader'@'%';

FLUSH PRIVILEGES;
SQL

# Spec Delta

## Purpose

생성된 SQL이 무엇이든 분석 대상 DB를 바꾸거나 무겁게 만들지 못하게 실행하고, 개인정보 컬럼의 값이 LLM으로 나가지 않게 한다. 목표와 수용 기준은 PRD F-003을 따른다.

## ADDED Requirements

### Requirement: 검사를 거친 SQL만 실행 (F-003)

밖에서 받은 SQL(LLM이 만든 SQL, 저장한 대시보드의 SQL)을 분석 대상 DB에 실행하는 경로는 하나여야 하고, 그 경로는 실행 전에 이 스펙의 SQL 검사를 모두 거쳐야 한다(SHALL). 검사에 걸린 SQL은 DB에 보내지 않고 오류를 돌려주어야 한다(SHALL). DB에는 검사한 SQL을 다시 쓴 문장을 보내, 검사한 것과 실행하는 것이 같아야 한다(SHALL). 연결 확인처럼 시스템이 정해 둔 조회는 이 경로 밖에 둘 수 있다.

#### Scenario: 검사에 걸린 SQL은 DB에 닿지 않음
- **WHEN** 검사에 걸리는 SQL을 실행하려 한다
- **THEN** DB에는 아무 문장도 보내지 않고, 검사 오류를 돌려준다

### Requirement: 실행되는 주석과 힌트 거부 (F-003)

시스템은 MySQL이 주석 안에서도 실행하거나 해석하는 부분(`/*! ... */` 실행 주석, `/*+ ... */` 옵티마이저 힌트)이 있는 SQL을 거부해야 한다(SHALL). 힌트로 실행 시간 상한을 바꾸지 못하게 하기 위해서다.

#### Scenario: 실행 주석
- **WHEN** `SELECT 1 /*!50000 , SLEEP(10) */`를 실행하려 한다
- **THEN** "SELECT가 아닌 SQL" 오류로 거부된다

#### Scenario: 실행 시간 힌트
- **WHEN** `SELECT /*+ MAX_EXECUTION_TIME(600000) */ COUNT(*) FROM orders`를 실행하려 한다
- **THEN** "SELECT가 아닌 SQL" 오류로 거부된다

#### Scenario: 보통 주석
- **WHEN** `-- 주문 수` 같은 보통 주석이 붙은 SELECT를 실행한다
- **THEN** 검사를 통과하고, DB에 보내는 문장에는 주석이 없다

### Requirement: SELECT 문 하나만 허용 (F-003)

시스템은 SELECT 조회 문 하나만 실행해야 한다(SHALL). WITH(CTE)와 UNION으로 이루어진 조회는 SELECT로 본다. INSERT, UPDATE, DELETE, REPLACE, DDL, CALL, SET 같은 다른 문, 여러 문, 해석할 수 없는 SQL, 잠금이나 파일 쓰기가 붙은 SELECT는 거부해야 한다(SHALL).

#### Scenario: 단순 SELECT
- **WHEN** `SELECT order_status, COUNT(*) FROM orders GROUP BY order_status`를 실행한다
- **THEN** 검사를 통과해 실행된다

#### Scenario: CTE와 UNION
- **WHEN** WITH 절이나 UNION으로 이루어진 조회를 실행한다
- **THEN** 검사를 통과해 실행된다

#### Scenario: 쓰기 문
- **WHEN** INSERT, UPDATE, DELETE, REPLACE 문을 각각 실행하려 한다
- **THEN** 모두 "SELECT가 아닌 SQL" 오류로 거부된다

#### Scenario: DDL
- **WHEN** CREATE, ALTER, DROP, TRUNCATE 문을 각각 실행하려 한다
- **THEN** 모두 "SELECT가 아닌 SQL" 오류로 거부된다

#### Scenario: 여러 문
- **WHEN** `SELECT 1; DELETE FROM orders`처럼 문이 둘 이상인 SQL을 실행하려 한다
- **THEN** "여러 문" 오류로 거부되고 어느 문도 실행되지 않는다

#### Scenario: 잠금이나 파일 쓰기가 붙은 SELECT
- **WHEN** `FOR UPDATE`, `FOR SHARE`, `LOCK IN SHARE MODE`, `INTO OUTFILE`, `INTO DUMPFILE`, `INTO @변수`가 붙은 SELECT를 실행하려 한다
- **THEN** "SELECT가 아닌 SQL" 오류로 거부된다

#### Scenario: 해석할 수 없는 SQL
- **WHEN** 문법이 틀린 SQL을 실행하려 한다
- **THEN** "SQL 해석 실패" 오류로 거부된다

### Requirement: 허용 테이블만 조회 (F-003)

SQL이 읽는 모든 테이블(서브쿼리, 조인, CTE 안 포함)은 설정한 스키마의 허용 테이블이어야 한다(SHALL). CTE 이름은 테이블로 보지 않는다. 다른 스키마(시스템 스키마와 서비스 DB 포함)를 가리키는 테이블은 거부해야 한다(SHALL).

#### Scenario: 허용 테이블만 씀
- **WHEN** 허용 테이블 orders와 customers를 조인하는 SQL을 실행한다
- **THEN** 검사를 통과한다

#### Scenario: 허용하지 않은 테이블
- **WHEN** 허용 목록에 없는 테이블을 서브쿼리에서 읽는 SQL을 실행하려 한다
- **THEN** "허용하지 않은 테이블" 오류로 거부되고, 오류에 그 테이블 이름이 있다

#### Scenario: 다른 스키마
- **WHEN** `information_schema.tables`, `mysql.user`, `askflow.artifact`처럼 다른 스키마의 테이블을 읽으려 한다
- **THEN** "허용하지 않은 테이블" 오류로 거부된다

#### Scenario: CTE 이름
- **WHEN** `WITH monthly AS (SELECT ... FROM orders) SELECT * FROM monthly`를 실행한다
- **THEN** monthly는 테이블로 보지 않고, 검사를 통과한다

### Requirement: 부작용이 있는 함수 거부 (F-003)

시스템은 DB를 붙잡아 두거나 서버 파일·잠금에 닿는 함수를 쓰는 SQL을 거부해야 한다(SHALL). 대상은 SLEEP, BENCHMARK, LOAD_FILE, GET_LOCK, RELEASE_LOCK, RELEASE_ALL_LOCKS다.

#### Scenario: SLEEP
- **WHEN** `SELECT SLEEP(100)`을 실행하려 한다
- **THEN** "허용하지 않은 함수" 오류로 거부되고, 오류에 함수 이름이 있다

#### Scenario: LOAD_FILE
- **WHEN** `SELECT LOAD_FILE('/etc/passwd')`를 실행하려 한다
- **THEN** "허용하지 않은 함수" 오류로 거부된다

### Requirement: 행 수 상한 (F-003)

시스템은 한 번 실행에서 설정한 행 수 상한보다 많은 행을 돌려주지 않아야 한다(SHALL NOT). LIMIT이 없는 SQL에는 상한을 넘는지 알 수 있는 LIMIT을 붙이고, 상한보다 큰 LIMIT은 줄여야 한다(SHALL). 결과가 상한을 넘으면 결과 없이 "행 수 상한 초과" 오류를 돌려주어야 한다(SHALL). 오류에는 상한 값과 바꿀 설정 키가 있어야 한다.

#### Scenario: 상한 안의 결과
- **WHEN** 행 수 상한이 1,000이고 결과가 30행인 LIMIT 없는 SQL을 실행한다
- **THEN** 30행이 돌아온다

#### Scenario: 상한을 넘는 결과
- **WHEN** 행 수 상한이 1,000이고 결과가 5,000행인 LIMIT 없는 SQL을 실행한다
- **THEN** 행은 돌려주지 않고 "행 수 상한 초과" 오류에 상한 1,000과 설정 키가 담긴다

#### Scenario: 상한보다 작은 LIMIT
- **WHEN** 행 수 상한이 1,000이고 `LIMIT 10`이 있는 SQL을 실행한다
- **THEN** LIMIT 10이 그대로 적용되어 최대 10행이 돌아온다

#### Scenario: 상한보다 큰 LIMIT
- **WHEN** 행 수 상한이 1,000이고 `LIMIT 100000`이 있는 SQL을 실행했는데 결과가 1,000행을 넘는다
- **THEN** "행 수 상한 초과" 오류가 돌아온다

#### Scenario: 상한을 설정으로 바꿈
- **WHEN** 행 수 상한을 50으로 설정하고 결과가 80행인 SQL을 실행한다
- **THEN** "행 수 상한 초과" 오류가 돌아오고, 오류의 상한 값은 50이다

### Requirement: 실행 시간 상한 (F-003)

시스템은 설정한 실행 시간 상한을 DB 쪽과 앱 쪽 모두에 걸어야 한다(SHALL). 상한을 넘은 실행은 DB에서 중단되어야 하고, 결과 없이 "실행 시간 상한 초과" 오류를 돌려주어야 한다(SHALL). 오류에는 상한 값과 바꿀 설정 키가 있어야 한다.

#### Scenario: 상한을 넘는 쿼리
- **WHEN** 실행 시간 상한이 1초이고 2초 넘게 걸리는 SQL을 실행한다
- **THEN** 실행은 중단되고 "실행 시간 상한 초과" 오류에 상한 1초와 설정 키가 담긴다

#### Scenario: 상한 안의 쿼리
- **WHEN** 실행 시간 상한이 10초이고 1초 안에 끝나는 SQL을 실행한다
- **THEN** 결과가 돌아오고, 결과에 걸린 시간이 있다

### Requirement: 읽기 전용 세션 (F-003)

분석 대상 DB의 모든 연결은 읽기 전용 트랜잭션으로 열어야 한다(SHALL). SQL 검사에 틈이 있더라도 쓰기는 DB가 거부해야 한다.

#### Scenario: 검사를 거치지 않은 쓰기
- **WHEN** 커넥터가 연 세션에 검사를 거치지 않은 INSERT 문을 직접 보낸다
- **THEN** DB가 읽기 전용 트랜잭션이라는 이유로 거부하고, 데이터는 바뀌지 않는다

### Requirement: 실행 결과 형식 (F-003)

실행이 성공하면 열 이름 목록, 행 목록, 행 수, 걸린 시간, 실제로 실행한 SQL(LIMIT을 붙인 뒤)을 돌려주어야 한다(SHALL). 열마다 개인정보 컬럼에서 나온 값인지 표시해야 한다(SHALL).

#### Scenario: 성공한 실행
- **WHEN** 검사를 통과한 SQL을 실행해 3행이 나온다
- **THEN** 결과에 열 이름, 3개의 행, 행 수 3, 걸린 시간, 실행한 SQL이 있다

### Requirement: 실행 오류 형식 (F-003)

실행이 실패하면 오류 종류, 한국어 메시지, 원인, 확인할 곳을 돌려주어야 한다(SHALL). 오류 종류는 SELECT가 아닌 SQL, 여러 문, SQL 해석 실패, 허용하지 않은 테이블, 허용하지 않은 함수, 행 수 상한 초과, 실행 시간 상한 초과, 연결 실패, DB 오류 중 하나다. 오류 메시지에는 비밀번호를 넣지 않아야 한다(SHALL NOT).

#### Scenario: 없는 컬럼
- **WHEN** 허용 테이블에 없는 컬럼을 읽는 SQL을 실행한다
- **THEN** "DB 오류"가 돌아오고, 원인에 DB가 알려 준 오류 내용이 있다

#### Scenario: 연결이 끊긴 상태
- **WHEN** DB가 꺼진 상태에서 SQL을 실행한다
- **THEN** "연결 실패" 오류가 돌아오고, 확인할 곳에 연결 상태 확인이 있다

### Requirement: LLM에 보낼 실행 오류 (F-003)

LLM에 보낼 실행 오류는 실행 오류에서 DB가 알려 준 오류 내용 원문을 뺀 것이어야 한다(SHALL). DB 오류의 원문에는 컬럼 값이 담길 수 있기 때문이다. DB 오류는 오류 종류, 한국어 메시지, DB 오류 코드와 SQLState만 남긴다. 검사와 상한에 걸린 오류는 SQL과 설정에서 온 원인(거부한 테이블·함수 이름, 상한 값)을 남긴다. 화면에 보이는 실행 오류는 원문을 그대로 둔다.

#### Scenario: 값을 담은 DB 오류
- **WHEN** 인자 값을 오류 메시지에 넣는 함수에 개인정보 컬럼을 넘겨 DB 오류가 난 SQL의 실행 오류로 LLM에 보낼 오류를 만든다
- **THEN** LLM에 보낼 오류에는 "DB 오류", 한국어 메시지, DB 오류 코드와 SQLState가 있고, 그 컬럼 값과 DB가 알려 준 오류 내용 원문은 어디에도 없다

#### Scenario: 화면용 오류는 그대로
- **WHEN** DB 오류가 난 SQL을 실행한다
- **THEN** 실행 오류 자체의 원인에는 DB가 알려 준 오류 내용이 남고, LLM에 보낼 오류에서만 빠진다

#### Scenario: 검사에 걸린 오류
- **WHEN** 허용하지 않은 테이블을 읽는 SQL의 실행 오류로 LLM에 보낼 오류를 만든다
- **THEN** LLM에 보낼 오류에는 "허용하지 않은 테이블"과 그 테이블 이름이 남는다

### Requirement: 개인정보 컬럼 값은 LLM에 보내지 않음 (F-003)

LLM에 보낼 결과는 실행 결과에서 개인정보 컬럼의 값이 담긴 열을 뺀 것이어야 한다(SHALL). 개인정보 컬럼을 그대로 고르거나, 별칭을 붙이거나, 집계가 아닌 함수로 바꾼 열, `SELECT *`로 들어온 열은 모두 개인정보 열로 본다. 개인정보 컬럼을 COUNT 안에서만 쓴 열은 개인정보 열로 보지 않는다. MIN, MAX, GROUP_CONCAT처럼 원래 값을 돌려줄 수 있는 집계는 개인정보 열로 본다. 뷰와 생성 컬럼은 정의를 따라가, 개인정보 컬럼에서 나온 뷰의 열과 개인정보 컬럼으로 계산하는 생성 컬럼도 개인정보 열로 본다. 뷰의 정의를 읽거나 해석할 수 없으면 그 뷰의 모든 열을 개인정보 열로 본다. 어디서 온 열인지 알 수 없으면 개인정보 열로 본다. 뺀 열은 이름만 남긴다.

#### Scenario: 개인정보 컬럼을 그대로 고름
- **WHEN** 개인정보 컬럼 `customers.customer_zip_code_prefix`와 `customer_state`를 고르는 SQL의 결과로 LLM에 보낼 결과를 만든다
- **THEN** LLM에 보낼 결과에는 `customer_state` 열만 있고, 뺀 열 이름에 `customer_zip_code_prefix`가 있다

#### Scenario: 별칭과 함수
- **WHEN** 개인정보 컬럼을 `AS zip`으로 고르거나 `LEFT(customer_zip_code_prefix, 2)`로 바꿔 고른다
- **THEN** 두 열 모두 LLM에 보낼 결과에서 빠진다

#### Scenario: SELECT *
- **WHEN** 개인정보 컬럼이 있는 테이블을 `SELECT *`로 읽는다
- **THEN** 그 테이블의 개인정보 컬럼 열은 LLM에 보낼 결과에서 빠진다

#### Scenario: COUNT 안에서만 씀
- **WHEN** `COUNT(DISTINCT customer_unique_id)`처럼 개인정보 컬럼을 COUNT 안에서만 쓴 열이 있다
- **THEN** 그 열은 LLM에 보낼 결과에 남는다

#### Scenario: 값을 돌려주는 집계
- **WHEN** `MAX(customer_zip_code_prefix)`처럼 개인정보 컬럼의 값을 돌려줄 수 있는 집계로 고른 열이 있다
- **THEN** 그 열은 LLM에 보낼 결과에서 빠진다

#### Scenario: 서브쿼리를 거친 열
- **WHEN** `SELECT z FROM (SELECT customer_zip_code_prefix AS z FROM customers) t`처럼 서브쿼리나 CTE를 거쳐 개인정보 컬럼을 고른다
- **THEN** 그 열은 LLM에 보낼 결과에서 빠진다

#### Scenario: 뷰를 거친 열
- **WHEN** 개인정보 컬럼 `customers.customer_zip_code_prefix`를 `region_code`라는 이름으로 내보내는 뷰가 허용 테이블에 있고, 설정의 개인정보 컬럼에는 원래 테이블의 컬럼만 적은 채 그 뷰의 `region_code`를 고른다
- **THEN** 그 열은 LLM에 보낼 결과에서 빠진다

#### Scenario: 개인정보 컬럼으로 만든 생성 컬럼
- **WHEN** 개인정보 컬럼을 잘라 계산하는 생성 컬럼을 고른다
- **THEN** 그 열은 LLM에 보낼 결과에서 빠진다

#### Scenario: 정의를 읽을 수 없는 뷰
- **WHEN** 정의를 읽거나 해석할 수 없는 뷰의 열을 고른다
- **THEN** 그 뷰에서 온 열은 모두 LLM에 보낼 결과에서 빠진다

#### Scenario: 화면용 결과는 그대로
- **WHEN** 개인정보 컬럼을 고른 SQL을 실행한다
- **THEN** 실행 결과 자체에는 그 열이 개인정보 열로 표시된 채 남고, LLM에 보낼 결과에서만 빠진다

# Dill

피클 앱에서 입력한 주문을 접수하고, 매장 매니저가 주문 내역과 수령 코드를 확인하는 FastAPI 서버입니다. 운영 주소는 `https://pichle.ionya.ooo`이며, Pydantic DTO로 요청·응답을 검증하고 SQLAlchemy 2 ORM·Session으로 자체 PostgreSQL에 저장합니다. 앱에 DB 접속 정보를 포함하지 않습니다.

## 코드 구조

`run.py`는 HTTP 서버를 실행하는 짧은 진입점이며, 서버 구현은 `app` 아래에 둡니다. DB 테이블은 서버 기동 전에 `init.sql`로 준비합니다.

```text
dill/
├── run.py                 # HTTP 서버 실행
├── init.sql               # 기동 전 수동 테이블·인덱스 생성
├── static/
│   ├── store.js           # Redux 전역 상태
│   ├── api.js             # Bearer HTTP 요청
│   ├── socket.js          # WebSocket·토큰 갱신
│   └── vendor/redux.mjs    # 로컬 Redux 배포 파일
└── app/
    ├── main.py            # FastAPI 앱·라우터·미들웨어 조립
    ├── core/              # 설정·DB Session·JSON 로깅·인증·공통 미들웨어
    ├── router/            # orders·manager·pages HTTP 라우터
    ├── model/             # SQLAlchemy ORM 모델·주문 저장소
    └── schema/            # Pydantic 주문·매니저 요청/응답 DTO
```

설정 파일과 로그의 기준 폴더는 계속 `dill` 루트입니다. Docker 내부에서는 `/app/config.yml`과 `/app/logs`를 사용하므로 소스 폴더 정리로 볼륨 경로가 바뀌지 않습니다.

## config.yml

`dill/config.yml`에 DB 접속 정보를 입력합니다. 파일이 없으면 아래 YAML 예제로 작성합니다. 실제 설정 파일은 Git 추적에서 제외합니다.

```yaml
database:
  host: "localhost"
  port: 5432
  name: "pichle"
  user: "pichle"
  password: ""
  connect_timeout: 5

manager:
  username: "manager"
  password: ""
  jwt_secret: ""

log:
  level: "INFO"
  path: "logs/app.log"
  handler: ["terminal", "file"]
  backup_count: 10
  timezone: "Asia/Seoul"
```

`database.host`, `port`, `name`, `user`, `password`를 자체 DB에 맞게 지정합니다. DB 연결은 SSL을 사용하지 않으며 `sslmode` 설정은 받지 않습니다. DB·계정과 주문 테이블은 미리 준비해야 합니다. 서버는 시작할 때 연결과 필수 테이블 준비 상태만 조회하며 DB·테이블을 자동으로 생성하거나 변경하지 않습니다. 테이블은 아래 [DB 초기화](#db-초기화)의 `init.sql`을 수동으로 실행해 준비합니다.

컨테이너의 `localhost`는 컨테이너 자신입니다. PostgreSQL이 Docker 호스트에서 실행된다면 `database.host`에는 워크플로가 제공하는 `host.docker.internal` 또는 실제 DB 주소를 입력합니다.

매니저 비밀번호는 `manager.password`에 8자 이상으로 입력합니다. 비워두면 매니저 로그인과 관리 API는 잠깁니다. 운영에서는 `manager.jwt_secret`에 32자 이상의 고정된 임의 문자열을 넣으세요. JWT 유효 기간은 **10분(600초)**이며 별도 refresh token은 없습니다. 키를 비워두면 프로세스마다 임의 키를 사용하므로 재시작 후 이전 토큰은 사용할 수 없습니다. 고정된 키와 비밀번호를 유지하면 발급한 JWT는 재시작 후에도 만료 시각까지 유효합니다.

문자열 값에서 `${DB_PASSWORD}`처럼 환경변수를 참조할 수도 있습니다. 다른 설정 파일을 쓰려면 `DILL_CONFIG_PATH`에 경로를 지정합니다. 상대 경로는 현재 작업 폴더가 아닌 `dill` 폴더를 기준으로 해석합니다.

```powershell
$env:DILL_CONFIG_PATH = "E:\pichle-config\config.yml"
```

## 실행

먼저 [DB 초기화](#db-초기화)의 SQL을 실행합니다. 다음은 프로젝트에 포함된 Python 가상환경을 사용하는 Windows PowerShell 기준 서버 실행 명령입니다.

```powershell
Set-Location E:\dev\pichle
.\dill\.venv\Scripts\python.exe -m pip install -r dill/requirements.txt
.\dill\.venv\Scripts\python.exe dill/run.py
```

Linux 서버에서는 저장소 루트를 기준으로 다음과 같이 실행할 수 있습니다. 가상환경이 없을 때 먼저 생성합니다.

```bash
python3 -m venv dill/.venv
dill/.venv/bin/python -m pip install -r dill/requirements.txt
dill/.venv/bin/python dill/run.py
```

- 운영 매니저 화면: [https://pichle.ionya.ooo/manager](https://pichle.ionya.ooo/manager)
- API 문서: [https://pichle.ionya.ooo/docs](https://pichle.ionya.ooo/docs)
- 서버 상태: [https://pichle.ionya.ooo/health](https://pichle.ionya.ooo/health)

Cloudflare가 외부 HTTPS를 처리하며 FastAPI는 `0.0.0.0:8000`에서 HTTP로 실행합니다. 실행 주소와 포트는 `run.py`에 고정되어 있고 `config.yml`에는 `server` 블록이 없습니다. Cloudflare의 서비스 연결 대상은 Docker 호스트의 `http://<호스트>:41001`에 맞춥니다. 이 문서의 주소는 배포 구성이며 실제 인프라 배포를 수행한 기록은 아닙니다.

매니저 화면에는 서버가 실제로 받은 주문만 표시합니다. 첫 실행 때는 주문을 기다리는 빈 화면이며, 피클에서 주문을 전송하면 매장·메뉴·수량·옵션·수령 코드를 확인할 수 있습니다.

매니저는 `config.yml`에 지정한 계정으로 로그인합니다. 로그인 응답의 `accessToken`, `tokenType`, `expiresIn`, `issuedAt`, `expiresAt`을 화면에서 사용합니다. `issuedAt`·`expiresAt`은 밀리초 단위 시각입니다. 로그인 후 관리 HTTP 요청은 `Authorization: Bearer <accessToken>`으로 JWT를 전달하고 서버가 PyJWT로 검증합니다. 앱의 주문 접수는 Pleos 앱의 `X-API-Key` 헤더를 검사합니다. 로그인·로그아웃과 관리 상태 변경은 브라우저의 Origin과 요청 Host가 같은 요청만 처리합니다. Cloudflare가 브라우저에서 사용하는 Host를 유지해 전달해야 합니다.

프론트엔드는 토큰을 Redux 전역 상태의 `auth.accessToken`에만 저장합니다. 쿠키·localStorage·persist에는 저장하지 않으므로 페이지를 새로고침하거나 다시 열면 재로그인합니다. 로그아웃하면 Redux의 인증·주문 상태와 WebSocket·타이머를 정리합니다. 인메모리 세션 저장소는 없으며 로그인 시도 횟수 제한만 프로세스 메모리에 보관합니다.

## WebSocket 실시간 주문

매니저 화면은 로그인 후 같은 출처의 `/v1/manager/ws`에 연결합니다. 운영 연결은 `wss://pichle.ionya.ooo/v1/manager/ws`이며, 로컬 HTTP 화면에서는 `ws://`를 자동 선택합니다. Cloudflare 앞단의 HTTPS와 별개로 FastAPI 쪽 연결은 일반 HTTP/WebSocket을 사용합니다. 앞단 프록시가 WebSocket 업그레이드 요청을 전달해야 합니다.

연결 직후 다음 메시지로 인증합니다.

```json
{"type":"AUTH","accessToken":"<JWT>"}
```

서버의 `AUTH_OK`와 `ORDERS` 스냅샷을 받은 뒤 Redux 상태와 주문 화면을 갱신합니다. 주문 접수·상태 변경이 DB에 커밋되면 단일 프로세스 이벤트 브로커가 연결된 매니저에게 최신 `ORDERS` 스냅샷을 전송합니다. 기존 3초 HTTP 주문 폴링은 사용하지 않습니다.

| 메시지 | 용도 |
| --- | --- |
| `{"type":"SUBSCRIBE","storeId":"매장 ID"}` | 해당 매장으로 구독 범위 변경 |
| `{"type":"FETCH_ORDERS"}` | 주문 목록 수동 갱신 |
| `{"type":"PING"}` / `PONG` | 연결 유지 확인 |
| `{"type":"NEW_TOKEN"}` | 현재 JWT가 유효할 때 새 access token 요청 |

화면은 8분(480초)마다 `NEW_TOKEN`을 요청합니다. 응답 형식은 다음과 같으며 `issuedAt`·`expiresAt`은 밀리초 단위 시각입니다.

```json
{
  "type": "NEW_TOKEN",
  "accessToken": "<새 JWT>",
  "tokenType": "bearer",
  "expiresIn": 600,
  "issuedAt": 1795530000000,
  "expiresAt": 1795530600000
}
```

새 토큰으로 Redux의 인증 상태를 교체하고 이후 HTTP 요청에 사용합니다. 갱신은 현재 토큰이 만료되기 전에만 가능하며, 이미 만료됐다면 다시 로그인해야 합니다. 별도 refresh token이나 HTTP 토큰 재발급 엔드포인트는 없습니다. 로컬 Redux 파일과 라이선스를 `static/vendor`에 포함하므로 별도 npm 빌드 과정은 추가하지 않습니다.

## Docker 배포와 볼륨

GitHub Actions 진입점은 저장소 루트의 [`.github/workflows/cd.yml`](../.github/workflows/cd.yml)입니다. 기존 `dill/.github/workflows/cd.yml`을 루트로 옮겨 GitHub가 인식하는 워크플로 하나만 사용합니다. 워크플로는 `./dill`을 빌드 컨텍스트로 [Dockerfile](Dockerfile)을 사용합니다. 컨테이너 작업 폴더는 `/app`입니다.

| 항목 | 호스트 경로 | 컨테이너 경로 | 권한 |
| --- | --- | --- | --- |
| 설정 | `/home/server/pichle/config.yml` | `/app/config.yml` | 읽기 전용 |
| 로그 | `/home/server/pichle/logs` | `/app/logs` | 읽기·쓰기 |

`DILL_CONFIG_PATH=/app/config.yml`을 명시하고, 포트는 `41001:8000`으로 연결합니다. 설정 파일은 이미지에 복사하지 않습니다. [.dockerignore](.dockerignore)는 실제 설정·비밀번호 파일, 가상환경, Git 자료와 로그를 빌드 컨텍스트에서 제외합니다.

워크플로는 checkout → 이미지 빌드 → 기존 컨테이너 중지·삭제 → 컨테이너 생성의 네 단계입니다. 빌드 전에 `/home/server/pichle/config.yml`이 파일로 존재하는지 확인하고, 컨테이너 생성 단계에서 로그 폴더를 준비합니다. 이미지는 `pichle:latest`를 사용하며 볼륨은 `-v`로 연결합니다.

호스트 로그 폴더는 컨테이너 밖에 남아 재배포·컨테이너 재생성 후에도 로그와 회전 파일이 유지됩니다.

## JSON 로깅

기본 로그는 한 줄에 JSON 객체 하나인 UTF-8 형식으로 `logs/app.log`에 저장됩니다. 로컬에서는 `dill/logs/app.log`, 배포 컨테이너에서는 `/app/logs/app.log`, 호스트에서는 `/home/server/pichle/logs/app.log`입니다. 상대 경로는 `dill` 루트 기준이며 `log.path`는 `logs` 폴더 안의 경로만 허용합니다.

`Asia/Seoul` 기준 자정 이후 첫 로그 기록 때 이전 파일을 `app.log.YYYY-MM-DD`로 회전하고, 기본적으로 회전 파일을 최대 10개 보관합니다. 현재 `app.log`는 이 개수에 포함하지 않습니다. `log.backup_count`로 보관 개수를, `log.level`과 `log.handler`로 레벨과 출력 대상을 바꿀 수 있습니다. `terminal` 출력도 같은 JSON 형식입니다.

로그에는 `timestamp`, `level`, `name`, `message`, `request_id`, `filename`, `lineno`가 포함됩니다. 요청 로그는 HTTP 메서드, 경로 템플릿, 상태 코드와 처리 시간(`duration_ms`)을 추가합니다. 요청 ID는 `X-Request-ID`로 전달하거나 서버가 발급하며 응답 헤더에도 포함합니다. 주문 본문·쿼리 값·쿠키·인증 헤더·접속 비밀번호는 기록하지 않습니다. Uvicorn 기본 access 로그는 끄고 요청 미들웨어에서 한 번 수집합니다.

날짜별 파일 회전과 현재 실시간 이벤트 브로커는 **컨테이너당 Uvicorn 워커 한 개**로 실행하는 구성을 전제로 합니다. 여러 프로세스가 같은 로그 파일을 동시에 회전하도록 워커 수를 늘리지 않습니다.

## 피클 앱 연결

앱의 기본 서버 주소는 `https://pichle.ionya.ooo`입니다. 운영 환경에서는 에뮬레이터와 실제 기기 모두 이 HTTPS 주소를 사용합니다.

다른 서버를 사용하려면 앱의 **Pichle 이름을 7번 누르기 → 주문 서버**에서 주소를 지정합니다. `/v1/orders`는 붙이지 않습니다. 기존에 개발 서버 주소를 저장한 앱에서는 **기본값**을 눌러 운영 주소로 돌아갈 수 있습니다.

로컬 개발용 HTTP 예외는 `10.0.2.2`, `localhost`, `127.0.0.1`에만 적용됩니다. 에뮬레이터에서 개발 PC에 연결할 때는 주소를 `http://10.0.2.2:8000`으로 바꿀 수 있습니다. 매니저의 Bearer JWT 인증은 쿠키 설정을 사용하지 않습니다.

Android 17·대상 SDK 37 환경에서 로컬 네트워크에 접속할 때는 앱의 로컬 네트워크 접근 권한도 허용해야 합니다. 자세한 동작은 [Android 로컬 네트워크 권한 안내](https://developer.android.com/privacy-and-security/local-network-permission)를 참고하세요.

## 주문 API

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| `POST` | `/v1/orders` | 앱 키 확인 후 주문 접수 및 수령 코드 발급 |
| `GET` | `/v1/orders` | 주문 목록과 총 개수 |
| `GET` | `/v1/orders/{orderId}` | 주문 한 건 조회 |
| `PATCH` | `/v1/orders/{orderId}/status` | 주문 상태 변경 |
| `GET` | `/v1/stores` | 접수된 주문에서 확인한 매장 목록 |
| `GET` | `/health` | 서버 상태 |
| `GET` | `/v1/manager/session` | Bearer JWT 유효성 확인·토큰 재발급 없음 |
| `POST` | `/v1/manager/login` | 매니저 로그인 |
| `POST` | `/v1/manager/logout` | 매니저 로그아웃 |
| `WS` | `/v1/manager/ws` | 실시간 주문 구독·JWT 갱신 |

목록 조회에는 `storeId`, `status`, `limit`, `offset`을 사용할 수 있습니다. 예를 들어 `/v1/orders?status=RECEIVED&limit=100&offset=0`은 새로 접수된 주문을 최신순으로 반환합니다. `limit`의 최대값은 200이며 응답 형식은 `{"orders": [...], "total": 1}`입니다. 매장 목록의 응답은 `{"stores": [{"storeId": "...", "storeName": "..."}]}`입니다.

### 주문 전송 형식

Pleos 앱은 다음 헤더를 보내며, 서버는 같은 값인지 확인합니다.

```http
Content-Type: application/json
X-API-Key: pichle-pleos-pt-2026
```

키는 앱의 `DillOrderClient.APP_API_KEY`와 서버의 `app/core/security.py`에 동일하게 지정합니다. 이 키로는 매니저용 주문 조회·상태 변경을 할 수 없으며 해당 API에는 매니저 Bearer JWT가 필요합니다.

다음은 요청 형식 예시입니다.

```json
{
  "clientRequestId": "aa97ae8a-7bb9-4c2e-871a-035809e6ec79",
  "storeId": "songdo-store",
  "storeName": "송도점",
  "items": [
    {
      "menuItemId": "americano",
      "name": "아메리카노",
      "quantity": 2,
      "unitPriceKrw": null,
      "options": "얼음 적게"
    }
  ],
  "customerNote": ""
}
```

메뉴는 주문 한 건에 1~60개 항목, 각 항목의 수량은 1~20개를 받습니다. 가격을 모르는 메뉴는 `unitPriceKrw: null`로 전송합니다. 하나라도 가격이 없으면 `totalPriceKrw`도 `null`이며, 매니저 화면에서 확인이 필요한 금액으로 표시합니다. 알 수 없는 가격을 0원으로 만들지 않습니다. 결제 기능은 포함하지 않습니다.

신규 접수는 `201`과 함께 서버가 발급한 주문 ID·수령 코드를 반환합니다. 응답 형식 예시는 다음과 같습니다.

```json
{
  "orderId": "079b7acf-0551-4995-b098-17e99313fb82",
  "clientRequestId": "aa97ae8a-7bb9-4c2e-871a-035809e6ec79",
  "storeId": "songdo-store",
  "storeName": "송도점",
  "pickupCode": "P-000001",
  "status": "RECEIVED",
  "createdAt": "2026-11-24T12:00:00.000Z",
  "updatedAt": "2026-11-24T12:00:00.000Z",
  "items": [
    {
      "menuItemId": "americano",
      "name": "아메리카노",
      "quantity": 2,
      "unitPriceKrw": null,
      "options": "얼음 적게"
    }
  ],
  "customerNote": "",
  "totalPriceKrw": null
}
```

연결이 끊기거나 응답을 받지 못해 재전송할 때는 **같은 `clientRequestId`와 같은 주문 내용**을 사용합니다. 이미 접수된 요청이면 `200`과 기존 주문·코드를 반환하여 중복 접수를 막습니다. 같은 ID로 다른 주문을 보내면 `409`를 반환합니다. 새로운 주문에는 새로운 UUID를 사용합니다.

### 주문 상태

```text
RECEIVED → PREPARING → READY → COMPLETED
   └──────────┴─────────┴────→ CANCELLED
```

접수·준비 중·준비 완료 상태에서 취소할 수 있습니다. 완료·취소된 주문은 이전 상태로 돌릴 수 없습니다. 현재 상태와 같은 값을 다시 보내는 것은 허용합니다.

```json
{"status": "PREPARING"}
```

위 내용을 `PATCH /v1/orders/{orderId}/status`로 전송합니다. 허용하지 않는 전이는 `409`, 없는 주문은 `404`를 반환합니다.

## PostgreSQL 저장

요청·응답은 Pydantic DTO가 검증하고, 저장은 SQLAlchemy 2 ORM 모델과 Session이 담당합니다. psycopg는 SQLAlchemy의 PostgreSQL 드라이버로만 사용합니다. 주문 생성·수령 코드 발급·상태 변경은 Session 트랜잭션으로 처리합니다. 수령 코드는 DB의 증가 번호를 사용하며, 전송 ID의 고유 제약으로 중복 접수를 방지합니다. DB 연결의 SSL은 비활성화되어 있습니다. SQLite로 자동 전환하지 않으며, 기존 SQLite 파일의 자동 데이터 이전은 포함하지 않습니다.

## DB 초기화

테이블·인덱스 생성은 [init.sql](init.sql)을 수동으로 실행합니다. 먼저 사용할 DB를 준비한 뒤 그 DB에 연결해 서버 기동 전에 실행합니다. 기존 테이블·데이터를 삭제하지 않으며 기존 스키마를 변경하는 자동 마이그레이션은 수행하지 않습니다.

### DB와 계정이 아직 없는 경우

PostgreSQL 관리자 계정으로 대상 서버의 `psql`에 접속해 다음을 실행합니다. 이미 존재하는 계정·DB의 생성문은 각각 생략합니다. 아래의 DB·계정 이름은 `config.yml`의 `database.name`, `database.user`와 맞춥니다.

```sql
CREATE ROLE pichle LOGIN;
\password pichle
CREATE DATABASE pichle OWNER pichle;
```

`\password`가 요청하는 비밀번호를 직접 입력하고 같은 값을 `config.yml`의 `database.password`에 저장합니다. 비밀번호를 명령 인수나 SQL 리터럴로 남길 필요가 없습니다. `init.sql`은 DB나 계정 자체를 생성하지 않습니다.

`CREATE DATABASE`는 트랜잭션 밖에서 DB가 없을 때 한 번만 실행합니다. DB가 이미 있으면 생략하고 해당 DB에 연결합니다.

### SQL 파일로 테이블 생성

`dill` 폴더에서 `config.yml`과 같은 DB 호스트·계정으로 접속해 실행합니다. `DB_HOST`, `DB_USER`, 필요하면 DB 이름 `pichle`을 실제 값으로 바꿉니다. PostgreSQL SSL은 사용하지 않도록 지정하며 비밀번호는 프롬프트에서 입력합니다.

```bash
psql "host=DB_HOST port=5432 dbname=pichle user=DB_USER sslmode=disable" -W -v ON_ERROR_STOP=1 -f init.sql
```

이 파일은 연결한 DB에 `public.pichle_orders`와 두 인덱스를 생성합니다. DB 자체를 만들지는 않습니다. 명령은 문서 예시이며 여기서 실제 SQL을 실행하거나 DB에 접속하지 않았습니다.

이번 변경에서는 배포·서버 실행·DB 접속·빌드·테스트를 수행하지 않았습니다.

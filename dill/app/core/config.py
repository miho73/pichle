from dataclasses import dataclass, field
import os
from pathlib import Path
import re

import yaml


BASE_DIR = Path(__file__).resolve().parents[2]
MAX_CONFIG_BYTES = 65_536
ENV_VALUE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


class ConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    port: int
    name: str
    user: str
    password: str = field(repr=False)
    connect_timeout: int = 5


@dataclass(frozen=True)
class ManagerConfig:
    username: str
    password: str = field(repr=False)
    jwt_secret: str = field(repr=False)


@dataclass(frozen=True)
class LogConfig:
    level: str = "INFO"
    path: Path = BASE_DIR / "logs" / "app.log"
    backup_count: int = 10
    timezone: str = "Asia/Seoul"
    handlers: tuple[str, ...] = ("terminal", "file")


@dataclass(frozen=True)
class AppConfig:
    database: DatabaseConfig
    manager: ManagerConfig
    log: LogConfig = field(default_factory=LogConfig)


def _section(data: dict, name: str, allowed: set[str]) -> dict:
    value = data.get(name)
    if not isinstance(value, dict):
        raise ConfigError(f"config.yml의 {name} 항목을 확인해주세요.")
    if set(value) - allowed:
        raise ConfigError(f"config.yml의 {name} 항목에 지원하지 않는 설정 이름이 있습니다.")
    return value


def _text(data: dict, name: str, section: str, *, default=None, empty=False, secret=False, maximum=1024) -> str:
    value = data.get(name, default)
    if not isinstance(value, str):
        raise ConfigError(f"{section}.{name}은 문자열로 입력해주세요.")
    if not secret:
        value = value.strip()
    if (not empty and not value) or len(value) > maximum:
        raise ConfigError(f"{section}.{name}의 길이를 확인해주세요.")
    return value


def _integer(data: dict, name: str, section: str, default: int, minimum: int, maximum: int) -> int:
    value = data.get(name, default)
    if isinstance(value, str) and re.fullmatch(r"[0-9]+", value):
        value = int(value)
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ConfigError(f"{section}.{name}은 {minimum}~{maximum}의 정수로 입력해주세요.")
    return value


def _environment(value):
    if isinstance(value, dict):
        return {key: _environment(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_environment(item) for item in value]
    if not isinstance(value, str):
        return value

    def replace(match):
        name = match.group(1)
        if name not in os.environ:
            raise ConfigError(f"config.yml에서 참조한 환경변수 {name}이 설정되지 않았습니다.")
        return os.environ[name]

    return ENV_VALUE.sub(replace, value)


def load_config(config_path: str | Path | None = None) -> AppConfig:
    """Read configuration without logging its contents or connecting to the database."""
    supplied = config_path if config_path is not None else os.environ.get("DILL_CONFIG_PATH", "config.yml")
    path = Path(supplied).expanduser()
    if not path.is_absolute():
        path = BASE_DIR / path
    try:
        with path.open("rb") as source:
            raw = source.read(MAX_CONFIG_BYTES + 1)
    except FileNotFoundError:
        raise ConfigError("config.yml을 만들고 database, manager, log 설정을 입력해주세요.") from None
    except OSError:
        raise ConfigError("설정 파일을 읽지 못했습니다. DILL_CONFIG_PATH와 파일 권한을 확인해주세요.") from None
    if len(raw) > MAX_CONFIG_BYTES:
        raise ConfigError("config.yml이 너무 큽니다.")
    try:
        data = yaml.safe_load(raw.decode("utf-8-sig"))
    except UnicodeDecodeError:
        raise ConfigError("config.yml을 UTF-8 형식으로 저장해주세요.") from None
    except yaml.YAMLError as error:
        mark = getattr(error, "problem_mark", None)
        line = f" ({mark.line + 1}번째 줄)" if mark is not None else ""
        # PyYAML's original exception may contain a password-bearing source line.
        raise ConfigError(f"config.yml의 YAML 문법을 확인해주세요{line}.") from None
    if not isinstance(data, dict) or set(data) - {"database", "manager", "log"}:
        raise ConfigError("config.yml에는 database, manager, log 설정을 입력해주세요.")
    data = _environment(data)
    database = _section(data, "database", {"host", "port", "name", "user", "password", "connect_timeout"})
    manager = _section(data, "manager", {"username", "password", "jwt_secret"})
    log = data.get("log", {})
    if not isinstance(log, dict) or set(log) - {"level", "path", "handler", "backup_count", "timezone"}:
        raise ConfigError("config.yml의 log 설정 이름을 확인해주세요.")
    log_level = _text(log, "level", "log", default="INFO").upper()
    if log_level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
        raise ConfigError("log.level은 DEBUG, INFO, WARNING, ERROR, CRITICAL 중 하나입니다.")
    log_timezone = _text(log, "timezone", "log", default="Asia/Seoul")
    if log_timezone not in {"Asia/Seoul", "UTC"}:
        raise ConfigError("log.timezone은 Asia/Seoul 또는 UTC로 입력해주세요.")
    log_handlers = log.get("handler", ["terminal", "file"])
    if not isinstance(log_handlers, list) or not log_handlers or any(
        not isinstance(item, str) or item not in {"terminal", "file"} for item in log_handlers
    ):
        raise ConfigError("log.handler에는 terminal, file 중 사용할 항목을 입력해주세요.")
    log_path = Path(_text(log, "path", "log", default="logs/app.log"))
    if not log_path.is_absolute():
        log_path = BASE_DIR / log_path
    log_path = log_path.resolve()
    log_root = (BASE_DIR / "logs").resolve()
    if not log_path.is_relative_to(log_root) or log_path == log_root:
        raise ConfigError("log.path는 logs 폴더 안의 파일로 지정해주세요.")
    password = _text(manager, "password", "manager", default="", empty=True, secret=True)
    jwt_secret = _text(manager, "jwt_secret", "manager", default="", empty=True, secret=True, maximum=4096)
    if password and len(password) < 8:
        raise ConfigError("manager.password는 비워두거나 8자 이상으로 입력해주세요.")
    if jwt_secret and len(jwt_secret) < 32:
        raise ConfigError("manager.jwt_secret은 비워두거나 32자 이상으로 입력해주세요.")
    return AppConfig(
        database=DatabaseConfig(
            host=_text(database, "host", "database", default="localhost"),
            port=_integer(database, "port", "database", 5432, 1, 65535),
            name=_text(database, "name", "database", maximum=200),
            user=_text(database, "user", "database", maximum=200),
            password=_text(database, "password", "database", default="", empty=True, secret=True, maximum=4096),
            connect_timeout=_integer(database, "connect_timeout", "database", 5, 1, 60),
        ),
        manager=ManagerConfig(
            username=_text(manager, "username", "manager", default="manager", maximum=80),
            password=password,
            jwt_secret=jwt_secret,
        ),
        log=LogConfig(
            level=log_level,
            path=log_path,
            backup_count=_integer(log, "backup_count", "log", 10, 1, 365),
            timezone=log_timezone,
            handlers=tuple(dict.fromkeys(log_handlers)),
        ),
    )

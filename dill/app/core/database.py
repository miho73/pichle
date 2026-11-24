"""SQLAlchemy engine and short-lived Session factories for the PostgreSQL store."""

from sqlalchemy import URL, create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import DatabaseConfig


class Base(DeclarativeBase):
    pass


class Database:
    def __init__(self, config: DatabaseConfig):
        url = URL.create(
            "postgresql+psycopg",
            username=config.user,
            password=config.password,
            host=config.host,
            port=config.port,
            database=config.name,
        )
        self.engine = create_engine(
            url,
            connect_args={
                "sslmode": "disable",
                "connect_timeout": config.connect_timeout,
                "application_name": "pichle-dill",
                "options": "-c statement_timeout=5000 -c lock_timeout=5000 -c timezone=UTC",
            },
            isolation_level="READ COMMITTED",
            pool_pre_ping=True,
            pool_size=5,
            max_overflow=5,
            pool_timeout=5,
            pool_recycle=1800,
            hide_parameters=True,
        )
        self.sessions = sessionmaker(bind=self.engine, class_=Session, expire_on_commit=False)
        snapshot_engine = self.engine.execution_options(isolation_level="REPEATABLE READ")
        self.snapshot_sessions = sessionmaker(bind=snapshot_engine, class_=Session, expire_on_commit=False)

    def dispose(self):
        self.engine.dispose()

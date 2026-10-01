import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    monkeypatch.setenv("DELIRIUMWATCH_DB", str(tmp_path / "study.db"))
    monkeypatch.setenv("DELIRIUMWATCH_TOKEN", "test-token")
    from app import db
    db.init_db()

@pytest.fixture
def client():
    from fastapi.testclient import TestClient
    from app.main import app
    with TestClient(app) as c:
        yield c

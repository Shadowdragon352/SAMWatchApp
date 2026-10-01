from __future__ import annotations

from typing import Any, Literal
import math
from pydantic import BaseModel, Field, field_validator


class EventIn(BaseModel):
    event_id: str = Field(min_length=8, max_length=100)
    study_id: str = Field(min_length=1, max_length=100)
    device_id: str = Field(default="unknown", max_length=200)
    ts_ms: int = Field(gt=0)
    type: str = Field(min_length=1, max_length=80)
    source: str = Field(default="watch", max_length=40)
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("study_id", mode="before")
    @classmethod
    def strip_study_id(cls, value: str) -> str:
        return value.strip()


    @field_validator("payload")
    @classmethod
    def finite_payload(cls, value: dict) -> dict:
        def check(item):
            if isinstance(item, float) and not math.isfinite(item):
                raise ValueError("Nonfinite numeric values are not valid research data")
            if isinstance(item, dict):
                for child in item.values(): check(child)
            elif isinstance(item, list):
                for child in item: check(child)
        check(value)
        return value


class IngestRequest(BaseModel):
    events: list[EventIn] = Field(min_length=1, max_length=1000)


class AnnotationIn(BaseModel):
    ts_ms: int = Field(gt=0)
    kind: Literal["state", "4at", "procedure", "note", "watch", "medication", "other"]
    value: str = Field(min_length=1, max_length=200)
    note: str = Field(default="", max_length=2000)


class AnnotationOut(AnnotationIn):
    id: int
    study_id: str

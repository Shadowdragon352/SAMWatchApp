if (-not $env:DELIRIUMWATCH_TOKEN) { throw "Set DELIRIUMWATCH_TOKEN to a long random value first" }
if (-not $env:DELIRIUMWATCH_TZ) { $env:DELIRIUMWATCH_TZ = "Australia/Melbourne" }
if (-not $env:DELIRIUMWATCH_HOST) { $env:DELIRIUMWATCH_HOST = "0.0.0.0" }
if (-not $env:DELIRIUMWATCH_PORT) { $env:DELIRIUMWATCH_PORT = "8000" }
uvicorn app.main:app --host $env:DELIRIUMWATCH_HOST --port $env:DELIRIUMWATCH_PORT

@echo off
if not exist venv\Scripts\activate.bat python -m venv venv
call venv\Scripts\activate
pip install -r requirements.txt
uvicorn --app-dir app main:app --port 8080

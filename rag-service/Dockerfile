FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
# Keep the CPU-oriented image small; no ML framework is required by main.py.
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

FROM python:3.12-slim

COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN mkdir -p /app/runtime /data

ENV PYTHONUNBUFFERED=1

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]

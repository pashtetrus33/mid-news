#!/bin/sh

# Ждём, пока база данных станет доступной
while ! pg_isready -h db -p 5432 -U postgres_user; do
  echo "Ждем базу данных..."
  sleep 2
done

echo "База данных доступна, запускаем приложение..."
exec java -jar app.jar

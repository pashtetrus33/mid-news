# Используем официальный образ Maven для сборки проекта
FROM maven:3.8.4-openjdk-17 AS build
WORKDIR /app

# Копируем только файлы зависимостей для кэширования
COPY pom.xml ./
# Копируем chrome и chromedriver в образ
COPY chrome ./chrome

# Устанавливаем зависимости
RUN mvn dependency:go-offline -B

# Копируем остальные файлы
COPY src ./src
# Сборка приложения
RUN mvn clean package -DskipTests

# Используем JDK для выполнения приложения
FROM openjdk:17-jdk-slim
WORKDIR /app

# Копируем собранный JAR файл
COPY --from=build /app/target/*.jar app.jar
COPY wait-for-db.sh ./
RUN chmod +x wait-for-db.sh  # Делаем скрипт исполняемым

# Устанавливаем PostgreSQL client для доступа к pg_isready
RUN apt-get update && \
    apt-get install -y postgresql-client && \
    rm -rf /var/lib/apt/lists/*

# Копируем chrome и chromedriver
COPY --from=build /app/chrome ./chrome

# Указываем команду для запуска приложения
ENTRYPOINT ["./wait-for-db.sh"]

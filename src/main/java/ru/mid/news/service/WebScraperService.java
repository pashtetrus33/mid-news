package ru.mid.news.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // Импортируем аннотацию
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.mid.news.entity.News;
import ru.mid.news.repository.NewsRepository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebScraperService {

    private final NewsRepository newsRepository;

    @Value("${app.scraper.incognito}")
    private boolean incognitoMode;


    @Value("${app.main.page}")
    private String url;

    @Value("${app.scraper.div.page-content}")
    private String pageContentDivClass;

    @Value("${app.scraper.div.announce-item}")
    private String announceItemClass;

    @Value("${app.scraper.cron.evening}")
    private String cronEvening;

    @Value("${app.scraper.cron.hourly}")
    private String cronHourly;

    @Value("${app.scraper.random-delay.min}")
    private int minDelay;

    @Value("${app.scraper.random-delay.max}")
    private int maxDelay;


    private WebDriver driver;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final List<News> newsList = new ArrayList<>();

    @PostConstruct
    public void init() {
        setupWebDriver();
    }

    private void setupWebDriver() {
        System.setProperty("webdriver.chrome.driver", "chrome/chromedriver.exe");

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.82 Safari/537.36");
        options.addArguments("--disable-blink-features=AutomationControlled");

        // Enable incognito mode if the flag is set to true
        if (incognitoMode) {
            options.addArguments("--incognito");
        }

        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
    }

    public void scrapeData() {
        try {
            driver.manage().window().maximize();
            driver.get(url);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.className(pageContentDivClass)));

            WebElement contentDiv = driver.findElement(By.className(pageContentDivClass));
            List<WebElement> elements = contentDiv.findElements(By.className(announceItemClass));

            for (WebElement element : elements) {
                processElement(element);
            }

            newsList.forEach(this::scrapeContent);
            newsRepository.saveAll(newsList);

        } catch (Exception e) {
            log.error("Ошибка при сборе данных: ", e);
        }
    }

    private void processElement(WebElement element) {
        String[] parts = element.getText().split("\n", 2);

        if (parts.length == 2) {
            LocalDateTime publicationDate = LocalDateTime.parse(parts[0].substring(0, 16).trim(), formatter);
            if (newsRepository.findByPublicationDate(publicationDate).isEmpty()) {
                News news = new News();
                news.setPublicationDate(publicationDate);
                news.setTitle(parts[1].trim());
                String link = element.findElement(By.tagName("a")).getAttribute("href");
                news.setUrl(link);

                newsList.add(news);
                log.info("Новость добавлена в лист: {}", publicationDate);
            } else {
                log.info("Дата и время новости уже есть в базе данных! {}", publicationDate);
            }
        }
    }

    private void scrapeContent(News news) {
        try {
            int delay = ThreadLocalRandom.current().nextInt(minDelay, maxDelay); // от 2 до 10 секунд
            log.info("Ждем {} миллисекунд перед открытием страницы: {}", delay, news.getUrl());
            Thread.sleep(delay);

            driver.get(news.getUrl());

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.className(pageContentDivClass)));

            WebElement contentDiv = driver.findElement(By.className(pageContentDivClass));
            String pageSource = contentDiv.getAttribute("outerHTML");

            LocalDate publishedDate = Optional.ofNullable(news.getPublicationDate())
                    .map(LocalDateTime::toLocalDate)
                    .orElse(LocalDate.now());

            String dateString = publishedDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            Path directoryPath = Paths.get(dateString);

            if (!Files.exists(directoryPath)) {
                Files.createDirectory(directoryPath);
            }

            AtomicInteger count = new AtomicInteger(1);
            File directory = directoryPath.toFile();
            if (directory.exists() && directory.isDirectory()) {
                File[] files = directory.listFiles((dir, name) -> name.endsWith(".html"));
                if (files != null && files.length > 0) {
                    count.set(files.length + 1); // Продолжаем нумерацию
                }
            }

            String fileName = count.getAndIncrement() + ".html";
            Path filePath = directoryPath.resolve(fileName);

            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(pageSource);
                log.info("Страница сохранена в файл: {}", filePath);
            }

            news.setContent(pageSource);

            Path indexPath = directoryPath.resolve("index.html");
            boolean indexExists = Files.exists(indexPath);

            try (FileWriter indexWriter = new FileWriter(indexPath.toFile(), true)) {
                if (!indexExists) {
                    indexWriter.write("<h1 style=\"font-weight: bold; text-align: left;\">Новости</h1>\n");
                }

                String localFileLink = "<a href=\"" + fileName + "\">" + news.getTitle() + "</a>";
                indexWriter.write("<p>" + news.getPublicationDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                        + " - " + localFileLink + "</p>\n");

                log.info("Добавлена информация в index.html для новости: {}", news.getTitle());
            } catch (IOException e) {
                log.warn("Ошибка при обновлении index.html для новости по ссылке: {}", news.getUrl(), e);
            }

        } catch (IOException | InterruptedException e) {
            log.warn("Ошибка при работе с файловой системой для новости по ссылке: {}", news.getUrl(), e);
        } catch (Exception e) {
            log.warn("Не удалось собрать содержимое для новости по ссылке: {}", news.getUrl(), e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Scheduled(cron = "${app.scraper.cron.evening}")
    @Scheduled(cron = "${app.scraper.cron.daily}")
    @Scheduled(cron = "${app.scraper.cron.hourly}")
    public void scheduledFetchNews() {
        scrapeData();
    }
}
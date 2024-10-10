package ru.mid.news.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mid.news.entity.News;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NewsRepository extends JpaRepository<News, Long> {
    List<News> findByPublicationDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    List<News> findByPublicationDate(LocalDateTime publicationDate);
}
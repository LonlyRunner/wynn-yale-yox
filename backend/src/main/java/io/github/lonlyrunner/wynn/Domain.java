package io.github.lonlyrunner.wynn;

import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity @Table(name="posts")
class Post {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(unique=true,nullable=false) String slug;
    @Column(nullable=false) String titleZh;
    @Column(nullable=false) String titleEn;
    String category;
    @Lob String contentZh;
    @Lob String contentEn;
    boolean published;
    Instant createdAt=Instant.now();
    protected Post() {}
    Post(String slug,String titleZh,String titleEn,String category){this.slug=slug;this.titleZh=titleZh;this.titleEn=titleEn;this.category=category;this.published=true;}
}

@Entity @Table(name="media_items")
class MediaItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String objectKey;
    String titleZh;
    String titleEn;
    String mediaType;
    Instant createdAt=Instant.now();
    protected MediaItem() {}
}

@Entity @Table(name="ai_jobs")
class AiJob {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    String provider;
    String type;
    @Column(length=4000) String prompt;
    String status;
    String resultObjectKey;
    Instant createdAt=Instant.now();
    protected AiJob() {}
    AiJob(String provider,String type,String prompt,String status){this.provider=provider;this.type=type;this.prompt=prompt;this.status=status;}
}

interface PostRepository extends JpaRepository<Post,Long>{java.util.List<Post> findByPublishedTrueOrderByCreatedAtDesc();}
interface MediaRepository extends JpaRepository<MediaItem,Long>{}
interface AiJobRepository extends JpaRepository<AiJob,Long>{}

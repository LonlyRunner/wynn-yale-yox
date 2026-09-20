package io.github.lonlyrunner.wynn;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "posts")
class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(unique = true, nullable = false) String slug;
    @Column(nullable = false) String titleZh;
    @Column(nullable = false) String titleEn;
    String category;
    String tags;
    String summaryZh;
    String summaryEn;
    String coverObjectKey;
    @Lob String contentZh;
    @Lob String contentEn;
    boolean published;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();

    protected Post() {}

    Post(String slug, String titleZh, String titleEn, String category) {
        this.slug = slug;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.category = category;
        this.published = true;
    }
}

@Entity
@Table(name = "comments")
class Comment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) Post post;
    @Column(nullable = false, length = 80) String author;
    @Column(length = 160) String email;
    @Column(nullable = false, length = 2000) String content;
    boolean approved;
    Instant createdAt = Instant.now();

    protected Comment() {}

    Comment(Post post, String author, String email, String content) {
        this.post = post;
        this.author = author;
        this.email = email;
        this.content = content;
    }
}

@Entity
@Table(name = "media_items")
class MediaItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, unique = true) String objectKey;
    String titleZh;
    String titleEn;
    String mediaType;
    @Column(length = 4000) String promptZh;
    @Column(length = 4000) String promptEn;
    int sortOrder;
    Instant createdAt = Instant.now();

    protected MediaItem() {}

    MediaItem(String objectKey, String titleZh, String titleEn, String mediaType, int sortOrder) {
        this.objectKey = objectKey;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.mediaType = mediaType;
        this.sortOrder = sortOrder;
    }
}

@Entity
@Table(name = "ai_jobs")
class AiJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    String provider;
    String type;
    @Column(length = 4000) String prompt;
    String status;
    String resultObjectKey;
    @Column(length = 2000) String resultUrl;
    String remoteId;
    @Column(length = 2000) String errorMessage;
    String guestId;
    String aspectRatio;
    String resolution;
    Integer duration;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();

    protected AiJob() {}

    AiJob(String provider, String type, String prompt, String guestId, String aspectRatio, String resolution, Integer duration) {
        this.provider = provider;
        this.type = type;
        this.prompt = prompt;
        this.guestId = guestId;
        this.aspectRatio = aspectRatio;
        this.resolution = resolution;
        this.duration = duration;
        this.status = "QUEUED";
    }
}

@Entity
@Table(name = "guest_quotas", uniqueConstraints = @UniqueConstraint(columnNames = "guestId"))
class GuestQuota {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false) String guestId;
    boolean imageUsed;
    boolean videoUsed;

    protected GuestQuota() {}

    GuestQuota(String guestId) { this.guestId = guestId; }
}

interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findByPublishedTrueOrderByCreatedAtDesc();
    Optional<Post> findBySlugAndPublishedTrue(String slug);
    Optional<Post> findBySlug(String slug);

    @Query("select p from Post p where p.published=true and (lower(p.titleZh) like lower(concat('%',:q,'%')) or lower(p.titleEn) like lower(concat('%',:q,'%')) or lower(p.category) like lower(concat('%',:q,'%')) or lower(p.tags) like lower(concat('%',:q,'%')) or lower(p.summaryZh) like lower(concat('%',:q,'%')) or lower(p.summaryEn) like lower(concat('%',:q,'%'))) order by p.createdAt desc")
    List<Post> searchPublished(@Param("q") String query);
}

interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByPostIdAndApprovedTrueOrderByCreatedAtAsc(Long postId);
}

interface MediaRepository extends JpaRepository<MediaItem, Long> {
    List<MediaItem> findAllByOrderBySortOrderAscCreatedAtDesc();
    Optional<MediaItem> findByObjectKey(String objectKey);
}

interface AiJobRepository extends JpaRepository<AiJob, Long> {
    List<AiJob> findAllByOrderByCreatedAtDesc();
}

interface GuestQuotaRepository extends JpaRepository<GuestQuota, Long> {
    Optional<GuestQuota> findByGuestId(String guestId);
}

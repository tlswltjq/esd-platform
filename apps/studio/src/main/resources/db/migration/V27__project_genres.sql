CREATE TABLE studio_project_genre (
    project_id BIGINT NOT NULL,
    genre_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (project_id, genre_code),
    CONSTRAINT fk_studio_genre_project FOREIGN KEY (project_id) REFERENCES studio_project(id) ON DELETE CASCADE
);

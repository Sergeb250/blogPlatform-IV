-- Runs at every startup, after Hibernate has created the tables (spring.jpa.defer-datasource-initialization).
-- Inserts only the roles that are missing, so a restart changes nothing.
INSERT INTO roles (name)
SELECT wanted.name
FROM (SELECT 'ADMIN' AS name UNION ALL SELECT 'AUTHOR' UNION ALL SELECT 'READER') wanted
WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.name = wanted.name);

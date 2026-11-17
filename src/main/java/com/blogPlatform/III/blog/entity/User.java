package com.blogPlatform.III.blog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.jdbc.Expectation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Entity
@Table(name = "users")
@SQLDelete(sql = "UPDATE users SET deleted_at = NOW(6) WHERE user_id = ? AND deleted_at IS NULL",
        verify = Expectation.RowCount.class)
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    // NULL for Google accounts: they never log in with a password
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'LOCAL'")
    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // Google's id for the account ("sub"); it stays the same when the Gmail address changes
    @Column(name = "provider_id", length = 100)
    private String providerId;

    // LAZY: post lists and comments only need the username, so they never pay for loading roles.
    // CASCADE on the join table: deleting a user row also removes its user_roles rows.
    @ManyToMany
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;


    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "author")
    private List<Post> posts = new ArrayList<>();

    public Set<RoleName> roleNames() {
        Set<RoleName> names = EnumSet.noneOf(RoleName.class);
        roles.forEach(role -> names.add(role.getName()));
        return names;
    }
}

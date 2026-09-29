package com.stove.auth.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "user_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String subject;

    @Column(nullable = false, unique = true, length = 200)
    private String email;

    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_account_role", joinColumns = @JoinColumn(name = "user_account_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<PlatformRole> roles = new HashSet<>();

    private UserAccount(String email, String passwordHash, Set<PlatformRole> roles) {
        this.subject = "usr_" + UUID.randomUUID().toString().replace("-", "");
        this.email = email.toLowerCase();
        this.passwordHash = passwordHash;
        this.status = AccountStatus.ACTIVE;
        this.roles.addAll(roles);
    }

    public static UserAccount creator(String email, String passwordHash) {
        return new UserAccount(email, passwordHash, Set.of(PlatformRole.CREATOR));
    }

    public static UserAccount withRoles(String email, String passwordHash, Set<PlatformRole> roles) {
        return new UserAccount(email, passwordHash, roles);
    }

    public boolean enabled() {
        return status == AccountStatus.ACTIVE;
    }
}

package com.aifilm.automation.account;

import com.aifilm.automation.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountControllerTest {

    @TempDir
    Path data;

    private AccountRepository accounts;
    private JobRepository jobs;
    private AccountController controller;

    @BeforeEach
    void setUp() {
        System.setProperty("afm.data.dir", data.toString());
        accounts = mock(AccountRepository.class);
        jobs = mock(JobRepository.class);
        controller = new AccountController(accounts, mock(AccountLoginService.class), jobs);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("afm.data.dir");
    }

    private AccountEntity account(AccountStatus status) {
        AccountEntity a = new AccountEntity();
        a.setId(7L);
        a.setStatus(status);
        when(accounts.findById(7L)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void confirmLogin_leased_blocked() {
        AccountEntity a = account(AccountStatus.LEASED);
        assertThrows(IllegalStateException.class, () -> controller.confirmLogin(7L));
        assertEquals(AccountStatus.LEASED, a.getStatus());
    }

    @Test
    void enable_needLogin_notMadeReady() {
        AccountEntity a = account(AccountStatus.NEED_LOGIN);
        assertThrows(IllegalStateException.class, () -> controller.enable(7L));
        assertEquals(AccountStatus.NEED_LOGIN, a.getStatus());
    }

    @Test
    void enable_disabled_becomesReady() {
        assertEquals(AccountStatus.READY, controller.enable(account(AccountStatus.DISABLED).getId()).getStatus());
    }

    @Test
    void delete_blockedWhileJobRunningOnAccount() {
        AccountEntity a = account(AccountStatus.READY);
        when(jobs.existsByAccountIdAndStatusIn(anyLong(), any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> controller.delete(7L));
        verify(accounts, never()).delete(a);
    }

    @Test
    void delete_renamesProfileInsteadOfDeleting() throws Exception {
        AccountEntity a = account(AccountStatus.READY);
        Path profile = data.resolve("profiles").resolve("acc_7");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("Cookies"), "x");

        controller.delete(7L);

        verify(accounts).delete(a);
        assertFalse(Files.exists(profile));
        List<Path> left;
        try (var s = Files.list(data.resolve("profiles"))) {
            left = s.toList();
        }
        assertEquals(1, left.size());
        assertTrue(left.get(0).getFileName().toString().startsWith("acc_7_deleted_"));
        assertTrue(Files.exists(left.get(0).resolve("Cookies")));
    }

    @Test
    void proxy_passwordWithDigitsAndColon_portFromHost() {
        assertEquals("http://user:12:34@1.2.3.4:8080", AccountController.validateProxy("http://user:12:34@1.2.3.4:8080"));
        assertEquals("1.2.3.4:8080:user:p4ss", AccountController.validateProxy("1.2.3.4:8080:user:p4ss"));
        assertThrows(IllegalArgumentException.class, () -> AccountController.validateProxy("http://user:123@1.2.3.4:99999"));
    }

    @Test
    void proxy_errorDoesNotEchoSecret() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AccountController.validateProxy("http://user:S3cret@host"));
        assertFalse(e.getMessage().contains("S3cret"));
    }
}

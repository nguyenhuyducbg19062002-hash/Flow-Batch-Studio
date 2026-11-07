package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Nhiều hồ sơ Chrome: tự thêm / bật / tạm nghỉ tài khoản Flow Extension theo email các extension đang nối. */
class FlowExtensionAccountSyncTest {

    private final List<AccountEntity> db = new ArrayList<>();
    private FlowExtensionAccount sync;

    @BeforeEach
    void setUp() {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findByEmail(anyString())).thenAnswer(inv -> db.stream()
                .filter(a -> a.getEmail().equals(inv.getArgument(0))).findFirst());
        when(accounts.findByProvider(any())).thenAnswer(inv -> db.stream()
                .filter(a -> a.getProvider() == inv.getArgument(0)).toList());
        when(accounts.save(any())).thenAnswer(inv -> {
            AccountEntity a = inv.getArgument(0);
            if (!db.contains(a)) {
                db.add(a);
            }
            return a;
        });
        sync = new FlowExtensionAccount(accounts);
        db.add(account(FlowExtensionAccount.EMAIL, Provider.FLOW_EXTENSION, AccountStatus.READY));
    }

    private static AccountEntity account(String email, Provider p, AccountStatus st) {
        AccountEntity a = new AccountEntity();
        a.setEmail(email);
        a.setProvider(p);
        a.setStatus(st);
        return a;
    }

    private AccountEntity get(String email) {
        return db.stream().filter(a -> a.getEmail().equals(email)).findFirst().orElseThrow();
    }

    @Test
    void addsOneAccountPerChromeEmailAndPausesSharedAccount() {
        sync.sync(List.of("A@Gmail.com ", "b@gmail.com"), false);

        assertEquals(AccountStatus.READY, get("a@gmail.com").getStatus());
        assertEquals(Provider.FLOW_EXTENSION, get("a@gmail.com").getProvider());
        assertEquals(AccountStatus.READY, get("b@gmail.com").getStatus());
        assertEquals(AccountStatus.NEED_LOGIN, get(FlowExtensionAccount.EMAIL).getStatus());
        assertEquals(3, db.size());

        sync.sync(List.of("a@gmail.com", "b@gmail.com"), false);   // lần sau: không thêm trùng
        assertEquals(3, db.size());
    }

    @Test
    void chromeClosedPausesItsAccountAndReconnectResumes() {
        sync.sync(List.of("a@gmail.com", "b@gmail.com"), false);
        get("b@gmail.com").setLastUsedAt(LocalDateTime.now());

        sync.sync(List.of("a@gmail.com"), false);
        assertEquals(AccountStatus.NEED_LOGIN, get("b@gmail.com").getStatus());
        assertEquals(FlowExtensionAccount.OFFLINE, get("b@gmail.com").getLastErrorMessage());
        assertEquals(AccountStatus.READY, get("a@gmail.com").getStatus());

        sync.sync(List.of("a@gmail.com", "b@gmail.com"), false);
        assertEquals(AccountStatus.READY, get("b@gmail.com").getStatus());
        assertNull(get("b@gmail.com").getLastErrorMessage());
    }

    @Test
    void leasedDisabledAndCoolingAccountsAreLeftAlone() {
        db.add(account("busy@gmail.com", Provider.FLOW_EXTENSION, AccountStatus.LEASED));
        db.add(account("off@gmail.com", Provider.FLOW_EXTENSION, AccountStatus.DISABLED));
        AccountEntity cool = account("cool@gmail.com", Provider.FLOW_EXTENSION, AccountStatus.COOLDOWN);
        cool.setCooldownUntil(LocalDateTime.now().plusHours(1));
        db.add(cool);

        sync.sync(List.of("off@gmail.com"), false);

        assertEquals(AccountStatus.LEASED, get("busy@gmail.com").getStatus());
        assertEquals(AccountStatus.DISABLED, get("off@gmail.com").getStatus());   // người dùng đã tắt: không tự bật
        assertEquals(AccountStatus.COOLDOWN, get("cool@gmail.com").getStatus());
    }

    @Test
    void sharedAccountComesBackWhenNoChromeReportsEmail() {
        sync.sync(List.of("a@gmail.com"), false);
        assertEquals(AccountStatus.NEED_LOGIN, get(FlowExtensionAccount.EMAIL).getStatus());

        sync.sync(List.of(), true);   // chỉ còn extension bản cũ (chưa rõ email)
        assertEquals(AccountStatus.READY, get(FlowExtensionAccount.EMAIL).getStatus());
        assertEquals(AccountStatus.NEED_LOGIN, get("a@gmail.com").getStatus());
    }

    @Test
    void sharedAccountInCooldownIsPausedAndStaysPausedWhileAllChromesAreClosed() {
        AccountEntity shared = get(FlowExtensionAccount.EMAIL);
        shared.setStatus(AccountStatus.COOLDOWN);
        shared.setCooldownUntil(LocalDateTime.now().plusMinutes(15));

        sync.sync(List.of("a@gmail.com"), false);
        assertEquals(AccountStatus.NEED_LOGIN, shared.getStatus());

        sync.sync(List.of(), false);   // đóng hết Chrome: không quay về tài khoản chung
        assertEquals(AccountStatus.NEED_LOGIN, shared.getStatus());
    }

    @Test
    void doesNotTouchSameEmailOfOtherProvider() {
        db.add(account("c@gmail.com", Provider.FLOW_BROWSER, AccountStatus.READY));
        sync.sync(List.of("c@gmail.com"), false);
        assertEquals(Provider.FLOW_BROWSER, get("c@gmail.com").getProvider());
        assertEquals(2, db.size());
        assertEquals(Optional.empty(), db.stream().filter(a -> a.getEmail().equals("c@gmail.com")
                && a.getProvider() == Provider.FLOW_EXTENSION).findFirst());
    }
}

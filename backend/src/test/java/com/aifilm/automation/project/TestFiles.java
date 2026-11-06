package com.aifilm.automation.project;

import org.mockito.invocation.InvocationOnMock;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tiện ích chung cho test: file ảnh "trông như thật" và TransactionTemplate chạy thẳng callback. */
final class TestFiles {

    private TestFiles() {}

    /** File PNG hợp lệ với MediaCheck (đủ lớn, đúng byte đầu). */
    static Path png(Path p, byte filler) throws IOException {
        byte[] b = new byte[200];
        Arrays.fill(b, filler);
        b[0] = (byte) 0x89; b[1] = 'P'; b[2] = 'N'; b[3] = 'G';
        Files.createDirectories(p.getParent());
        return Files.write(p, b);
    }

    static Path png(Path p) throws IOException {
        return png(p, (byte) 7);
    }

    static TransactionTemplate directTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(TestFiles::run);
        return tx;
    }

    private static Object run(InvocationOnMock inv) {
        return ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null);
    }
}

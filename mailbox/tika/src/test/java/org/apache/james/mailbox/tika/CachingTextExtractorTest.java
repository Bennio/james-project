/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.tika;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.apache.james.mailbox.extractor.ParsedContent;
import org.apache.james.mailbox.extractor.TextExtractor;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.api.NoopMetricFactory;
import org.apache.james.util.concurrency.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

public class CachingTextExtractorTest {

    private static final ParsedContent RESULT = new ParsedContent(Optional.of("content"), ImmutableMap.of());
    public static final String BIG_STRING = Strings.repeat("0123456789", 103 * 1024);
    private static final ParsedContent _2MiB_RESULT = new ParsedContent(Optional.of(BIG_STRING), ImmutableMap.of());
    private static final Function<Integer, InputStream> STREAM_GENERATOR =
        i -> new ByteArrayInputStream(String.format("content%d", i).getBytes(StandardCharsets.UTF_8));
    private static final Supplier<InputStream> INPUT_STREAM = () -> STREAM_GENERATOR.apply(1);
    private static final long CACHE_LIMIT_10_MiB = 10 * 1024 * 1024;
    private static final String CONTENT_TYPE = "application/bytes";

    private CachingTextExtractor textExtractor;
    private TextExtractor wrappedTextExtractor;

    @BeforeEach
    void setUp() throws Exception {
        wrappedTextExtractor = mock(TextExtractor.class);
        textExtractor = new CachingTextExtractor(wrappedTextExtractor,
            TikaConfiguration.DEFAULT_CACHE_EVICTION_PERIOD,
            CACHE_LIMIT_10_MiB,
            new NoopMetricFactory(),
            new NoopGaugeRegistry());

        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(RESULT);
    }

    @Test
    void extractContentShouldCallUnderlyingTextExtractor() throws Exception {
        textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE);

        verify(wrappedTextExtractor, times(1)).extractContent(any(), any());
        verifyNoMoreInteractions(wrappedTextExtractor);
    }

    @Test
    void extractContentShouldAvoidCallingUnderlyingTextExtractorWhenPossible() throws Exception {
        textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE);
        textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE);

        verify(wrappedTextExtractor, times(1)).extractContent(any(), any());
        verifyNoMoreInteractions(wrappedTextExtractor);
    }

    @Test
    void extractContentShouldPropagateCheckedException() throws Exception {
        IOException ioException = new IOException("Any");
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenThrow(ioException);

        assertThatThrownBy(() -> textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE))
            .isEqualTo(ioException);
    }

    @Test
    void extractContentShouldPropagateRuntimeException() throws Exception {
        RuntimeException runtimeException = new RuntimeException("Any");
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenThrow(runtimeException);

        assertThatThrownBy(() -> textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE))
            .isEqualTo(runtimeException);
    }

    @Test
    void cacheShouldEvictEntriesWhenFull() throws Exception {
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        IntStream.range(0, 10)
            .mapToObj(STREAM_GENERATOR::apply)
            .forEach(Throwing.consumer(inputStream -> textExtractor.extractContent(inputStream, CONTENT_TYPE)));

        assertThat(textExtractor.size())
            .isLessThanOrEqualTo(5);
    }

    @Test
    void olderEntriesShouldBeEvictedFirst() throws Exception {
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        IntStream.range(0, 10)
            .mapToObj(STREAM_GENERATOR::apply)
            .forEach(Throwing.consumer(inputStream -> textExtractor.extractContent(inputStream, CONTENT_TYPE)));

        reset(wrappedTextExtractor);
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        textExtractor.extractContent(STREAM_GENERATOR.apply(1), CONTENT_TYPE);

        verify(wrappedTextExtractor).extractContent(any(), any());
    }

    @Test
    void youngerEntriesShouldBePreservedByEviction() throws Exception {
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        IntStream.range(0, 10)
            .mapToObj(STREAM_GENERATOR::apply)
            .forEach(Throwing.consumer(inputStream -> textExtractor.extractContent(inputStream, CONTENT_TYPE)));

        reset(wrappedTextExtractor);
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        textExtractor.extractContent(STREAM_GENERATOR.apply(9), CONTENT_TYPE);

        verifyZeroInteractions(wrappedTextExtractor);
    }

    @Test
    void frequentlyAccessedEntriesShouldBePreservedByEviction() throws Exception {
        when(wrappedTextExtractor.extractContent(any(), any()))
            .thenReturn(_2MiB_RESULT);

        IntStream.range(0, 10)
            .mapToObj(STREAM_GENERATOR::apply)
            .peek(Throwing.consumer(any -> textExtractor.extractContent(STREAM_GENERATOR.apply(0), CONTENT_TYPE)))
            .forEach(Throwing.consumer(inputStream -> textExtractor.extractContent(inputStream, CONTENT_TYPE)));

        reset(wrappedTextExtractor);

        textExtractor.extractContent(STREAM_GENERATOR.apply(0), CONTENT_TYPE);

        verifyZeroInteractions(wrappedTextExtractor);
    }

    @RepeatedTest(10)
    void concurrentValueComputationShouldNotLeadToDuplicatedBackendAccess() throws Exception {
        ConcurrentTestRunner.builder()
            .threadCount(10)
            .build((a, b) -> textExtractor.extractContent(INPUT_STREAM.get(), CONTENT_TYPE))
            .run()
            .awaitTermination(1, TimeUnit.MINUTES);

        verify(wrappedTextExtractor, times(1)).extractContent(any(), any());
    }

}
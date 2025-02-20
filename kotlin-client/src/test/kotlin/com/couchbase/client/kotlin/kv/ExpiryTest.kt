/*
 * Copyright 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.kotlin.kv

import com.couchbase.client.kotlin.kv.Expiry.Companion.absolute
import com.couchbase.client.kotlin.kv.Expiry.Companion.relative
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit.DAYS

internal class ExpiryTest {

    @Test
    fun `none encodes to zero`() {
        assertEquals(0, Expiry.none().encode())
    }

    @Test
    fun `zero duration is invalid`() {
        assertThrows<IllegalArgumentException> {
            relative(Duration.ZERO)
        }
    }

    @Test
    fun `negative duration is invalid`() {
        assertThrows<IllegalArgumentException> {
            relative(Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `short durations are encoded verbatim`() {
        val longestVerbatimSeconds = DAYS.toSeconds(30) - 1
        assertEquals(longestVerbatimSeconds, relative(Duration.ofSeconds(longestVerbatimSeconds)).encode());
    }

    @Test
    fun `long durations are converted to absolute`() {
        val lowerBound = Instant.now().epochSecond + DAYS.toSeconds(30)
        val actual = relative(Duration.ofDays(30)).encode()
        val upperBound = Instant.now().epochSecond + DAYS.toSeconds(30)

        assertThat(actual, greaterThanOrEqualTo(lowerBound))
        assertThat(actual, lessThanOrEqualTo(upperBound))
    }

    @Test
    fun `zero instant is invalid`() {
        assertThrows<IllegalArgumentException> {
            absolute(Instant.EPOCH)
        }
    }

    @Test
    fun `negative instant is invalid`() {
        assertThrows<IllegalArgumentException> {
            absolute(Instant.ofEpochSecond(-1))
        }
    }

    @Test
    fun `instant in distant past is invalid`() {
        assertThrows<IllegalArgumentException> {
            absolute(Instant.ofEpochSecond(DAYS.toSeconds(30)))
        }
    }

    @Test
    fun `instant in recent past is encoded verbatim`() {
        val now = Instant.ofEpochSecond(DAYS.toSeconds(31)).epochSecond
        assertEquals(now, absolute(Instant.ofEpochSecond(now)).encode())
    }

    @Test
    fun `absolute are equal if they have the same epoch second`() {
        val now = Instant.parse("2020-01-01T00:00:00Z").epochSecond
        assertEquals(absolute(Instant.ofEpochSecond(now)), absolute(Instant.ofEpochSecond(now)))
        assertNotEquals(absolute(Instant.ofEpochSecond(now)), absolute(Instant.ofEpochSecond(now + 1)))
    }

    @Test
    fun `relative are equal if they have the same duration`() {
        assertEquals(relative(Duration.ofSeconds(60)), relative(Duration.ofMinutes(1)))
        assertNotEquals(relative(Duration.ofSeconds(61)), relative(Duration.ofMinutes(1)))
    }
}

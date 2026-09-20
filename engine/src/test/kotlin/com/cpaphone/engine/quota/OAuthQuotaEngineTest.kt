package com.cpaphone.engine.quota

import com.cpaphone.core.model.ProviderType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OAuthQuotaEngineTest {

    private val engine = OAuthQuotaEngine()

    @Test
    fun testParseAntigravityQuotaResponse() {
        val json = """
        {
          "groups": [
            {
              "displayName": "Gemini models",
              "description": "Models within this group: Gemini Flash, Gemini Pro",
              "buckets": [
                {
                  "bucketId": "quota-group-1-5h",
                  "displayName": "Five Hour Limit Remaining",
                  "remainingFraction": 0.77,
                  "resetTime": "2026-09-20T21:28:00.000Z"
                },
                {
                  "bucketId": "quota-group-1-weekly",
                  "displayName": "Weekly Limit Remaining",
                  "remainingFraction": 0.73,
                  "resetTime": "2026-09-23T15:42:00.000Z"
                }
              ]
            },
            {
              "displayName": "Claude and GPT models",
              "description": "Models within this group: Claude Opus, Claude Sonnet, GPT-OSS",
              "buckets": [
                {
                  "bucketId": "quota-group-2-5h",
                  "displayName": "Five Hour Limit Remaining",
                  "remainingFraction": 1.0,
                  "resetTime": "2026-09-20T18:00:00.000Z"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val quota = engine.parseAntigravityQuotaResponse("antigravity-test-01", json)

        assertEquals("antigravity-test-01", quota.credentialId)
        assertEquals(ProviderType.ANTIGRAVITY, quota.provider)
        assertEquals("Pro", quota.planType)
        assertEquals(2, quota.groups.size)

        val geminiGroup = quota.groups[0]
        assertEquals("Gemini models", geminiGroup.groupName)
        assertEquals(2, geminiGroup.windows.size)

        val fiveHour = geminiGroup.windows[0]
        assertEquals(77, fiveHour.remainingPercent)
        assertEquals("5h", fiveHour.windowType)

        val weekly = geminiGroup.windows[1]
        assertEquals(73, weekly.remainingPercent)
        assertEquals("weekly", weekly.windowType)

        val claudeGroup = quota.groups[1]
        assertEquals(100, claudeGroup.windows[0].remainingPercent)
    }

    @Test
    fun testParseCodexQuotaResponse() {
        val json = """
        {
          "plan_type": "free",
          "rate_limit_reset_credits": {
            "available_count": 0
          },
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 0,
              "limit_window_seconds": 18000,
              "reset_after_seconds": 14400
            },
            "secondary_window": {
              "used_percent": 0,
              "limit_window_seconds": 2592000,
              "reset_at": 1792074599
            }
          }
        }
        """.trimIndent()

        val quota = engine.parseCodexQuotaResponse("codex-test-01", json)

        assertEquals("codex-test-01", quota.credentialId)
        assertEquals(ProviderType.OPENAI_CODEX, quota.provider)
        assertEquals("Free", quota.planType)
        assertEquals(0, quota.resetCredits)
        assertEquals(1, quota.groups.size)

        val windows = quota.groups[0].windows
        assertEquals(2, windows.size)

        val primary = windows[0]
        assertEquals("5小时限额", primary.name)
        assertEquals(100, primary.remainingPercent)

        val monthly = windows[1]
        assertEquals("月度限额", monthly.name)
        assertEquals(100, monthly.remainingPercent)
        assertEquals("monthly", monthly.windowType)
        assertTrue(monthly.formattedCountdown.contains("天后") || monthly.formattedCountdown.contains("小时后"))
    }

    @Test
    fun testParseClaudeQuotaResponse() {
        val json = """
        {
          "five_hour": {
            "utilization": 0.25,
            "reset": "2026-09-20T20:00:00.000Z"
          },
          "seven_day": {
            "utilization": 0.40,
            "reset": "2026-09-25T12:00:00.000Z"
          }
        }
        """.trimIndent()

        val quota = engine.parseClaudeQuotaResponse("claude-test-01", json)

        assertEquals(ProviderType.CLAUDE, quota.provider)
        assertEquals(1, quota.groups.size)
        val windows = quota.groups[0].windows
        assertEquals(2, windows.size)
        assertEquals(75, windows[0].remainingPercent)
        assertEquals(60, windows[1].remainingPercent)
    }
}

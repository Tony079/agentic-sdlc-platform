package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A question the client already settled is not asked again when the model rewords it; different questions still are. */
class RepeatedQuestionTest {

    @Test
    void rewordedRepeatsAreRecognised() {
        assertThat(ClarificationRepository.sameQuestion(
                "Which resume file formats should the generator accept?",
                "What resume file formats should be accepted by the generator?")).isTrue();
        assertThat(ClarificationRepository.sameQuestion(
                "Should candidates be able to choose a colour theme for their portfolio website?",
                "Can the candidate choose the colour theme of the portfolio website?")).isTrue();
        assertThat(ClarificationRepository.sameQuestion(
                "Do you need the portfolio website hosted by us, or will candidates export and host it themselves?",
                "Will candidates host the portfolio website themselves, or should it be hosted by us?")).isTrue();
        assertThat(ClarificationRepository.sameQuestion("Is SSO required?", "is sso required")).isTrue();
    }

    @Test
    void differentQuestionsAreStillAsked() {
        assertThat(ClarificationRepository.sameQuestion(
                "Which resume file formats should the generator accept?",
                "Which languages should the portfolio website support?")).isFalse();
        assertThat(ClarificationRepository.sameQuestion(
                "What is the maximum resume file size?",
                "What is the maximum number of portfolio projects shown?")).isFalse();
        // Short questions need an exact match.
        assertThat(ClarificationRepository.sameQuestion("Is SSO required?", "Is MFA required?")).isFalse();
    }
}

package io.github.tony079.sdlc.billing.internal;

import java.util.UUID;

import io.github.tony079.sdlc.billing.internal.BrdQuoteService.Quote;
import io.github.tony079.sdlc.billing.internal.BrdQuoteService.Statement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GD-22 for organisation users: BRD quotes on requirements, and the organisation's monthly charges. */
@RestController
@ConditionalOnWebApplication
class BrdQuoteController {

    private final BrdQuoteService quotes;

    BrdQuoteController(BrdQuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/api/v1/requirements/{id}/brd-quote")
    Quote quote(@PathVariable UUID id) {
        return quotes.quote(id);
    }

    @PostMapping("/api/v1/requirements/{id}/brd-quote/{action}")
    Quote decide(@PathVariable UUID id, @PathVariable String action) {
        return quotes.decide(id, action);
    }

    @GetMapping("/api/v1/organisation/billing")
    Statement statement(@RequestParam String month) {
        return quotes.statement(BillingController.month(month));
    }
}

package com.jachai.billing.controller;

import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.service.PaymentBillingService;
import com.jachai.shared.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * JachAI's own checkout page. It looks like a payment gateway, but it only completes the order it was
 * opened for: it takes no card or wallet details (the form fields are never sent) and no real money moves.
 * The order is finished through the same verify, apply and receipt path as SSLCommerz and bKash.
 * The long key in the link is the only thing that authorises it, so the page is never cached or leaked
 * through a referrer.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/pay/demo")
public class DemoCheckoutController {
    private final PaymentBillingService billing;

    @GetMapping("/{orderId}")
    public String page(@PathVariable String orderId, @RequestParam(name = "k", required = false) String key,
                       Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        PaymentBillingService.DemoCheckoutView view;
        try {
            view = billing.demoCheckout(orderId, key);
        } catch (ApiException invalid) {
            return "redirect:/?billing=failed";
        }
        if (view.status() == PaymentOrderStatus.APPLIED || view.status() == PaymentOrderStatus.VERIFIED) {
            return "redirect:/?billing=verified";
        }
        if (view.status() != PaymentOrderStatus.CHECKOUT_READY) {
            return "redirect:/?billing=canceled";
        }
        model.addAttribute("orderId", view.orderId());
        model.addAttribute("amount", view.amountBdt().toPlainString());
        model.addAttribute("key", key);
        return "demo-checkout";
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<Void> pay(@PathVariable String orderId, @RequestParam(name = "k", required = false) String key) {
        try {
            billing.completeDemoPayment(orderId, key);
            return back("verified");
        } catch (ApiException invalid) {
            return back("failed");
        }
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String orderId, @RequestParam(name = "k", required = false) String key) {
        try {
            billing.cancelDemoPayment(orderId, key);
            return back("canceled");
        } catch (ApiException invalid) {
            return back("failed");
        }
    }

    private static ResponseEntity<Void> back(String outcome) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/?billing=" + outcome)).build();
    }
}

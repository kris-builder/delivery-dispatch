package org.example.delivery.fulfillment;

import java.net.URI;
import java.util.List;

import org.example.delivery.identity.EmployeePrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class InboundController {
    private final InboundBatches batches;
    private final InboundReceipts receipts;
    private final WaybillLookup waybills;

    InboundController(InboundBatches batches, InboundReceipts receipts, WaybillLookup waybills) {
        this.batches = batches;
        this.receipts = receipts;
        this.waybills = waybills;
    }

    @PostMapping("/staff/inbound-batches")
    ResponseEntity<InboundBatches.BatchDetail> importManifest(@AuthenticationPrincipal EmployeePrincipal employee,
                                                            @RequestBody InboundBatches.Manifest manifest) {
        var result = batches.importManifest(employee, manifest);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/staff/inbound-batches/" + result.batch().id())).body(result.batch())
                : ResponseEntity.ok(result.batch());
    }

    @GetMapping("/staff/inbound-batches")
    List<InboundBatches.BatchSummary> list(@AuthenticationPrincipal EmployeePrincipal employee) {
        return batches.list(employee);
    }

    @GetMapping("/staff/inbound-batches/{batchId}")
    InboundBatches.BatchDetail detail(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long batchId) {
        return batches.detail(employee, batchId);
    }

    @PostMapping("/staff/inbound-batches/{batchId}/receipts")
    ResponseEntity<InboundReceipts.Receipt> scan(@AuthenticationPrincipal EmployeePrincipal employee,
                                                 @PathVariable long batchId, @RequestBody ScanInput input,
                                                 @RequestHeader("Idempotency-Key") String key) {
        String waybill = InboundBatches.requiredText(input.waybillNo(), 64, "运单号");
        var operation = receipts.scan(employee, batchId, waybill, key);
        var result = operation.result();
        return operation.status() == 201
                ? ResponseEntity.created(URI.create("/api/v1/staff/inbound-receipts/" + result.receiptId())).body(result)
                : ResponseEntity.ok(result);
    }

    @GetMapping("/staff/inbound-receipts/{receiptId}")
    InboundReceipts.Receipt receipt(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long receiptId) {
        return receipts.receipt(employee, receiptId);
    }

    @GetMapping("/operations/INBOUND_RECEIPT/{key}")
    InboundReceipts.Operation operation(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable String key) {
        return receipts.operation(employee, key);
    }

    @GetMapping("/staff/parcels")
    WaybillLookup.Result lookup(@AuthenticationPrincipal EmployeePrincipal employee,
                                @RequestParam String waybillNo) {
        return waybills.find(employee, InboundBatches.requiredText(waybillNo, 64, "运单号"));
    }

    record ScanInput(JsonNode waybillNo) {}
}

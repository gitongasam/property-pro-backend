package com.propertypro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import com.propertypro.dto.PaymentDtos.ChannelRequest;
import com.propertypro.dto.PaymentDtos.ChannelResponse;
import com.propertypro.dto.PaymentDtos.StkPushRequest;
import com.propertypro.dto.PaymentDtos.StkPushResponse;
import com.propertypro.entity.Bank;
import com.propertypro.entity.Lease;
import com.propertypro.entity.PaymentChannel;
import com.propertypro.entity.Tenant;
import com.propertypro.integration.BankNotificationAdapter;
import com.propertypro.integration.DarajaClient;
import com.propertypro.repository.BankRepository;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.repository.PaymentChannelRepository;
import com.propertypro.repository.TenantRepository;
import com.propertypro.security.CryptoService;
import com.propertypro.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentChannelServiceImpl implements PaymentChannelService {

    private final PaymentChannelRepository channels;
    private final TenantRepository tenants;
    private final LeaseRepository leases;
    private final CryptoService crypto;
    private final DarajaClient daraja;
    private final PlanGate planGate;
    private final AppProperties props;
    private final JdbcClient jdbc;
    private final EntityManager em;
    private final ObjectMapper json;
    private final BankRepository banks;
    private final List<BankNotificationAdapter> bankConnectors;

    @Override
    @Transactional(readOnly = true)
    public List<ChannelResponse> list() {
        return channels.findByActiveTrueOrderByCreatedAtAsc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public ChannelResponse create(ChannelRequest req) {
        planGate.requireWritable();
        PaymentChannel c = new PaymentChannel();
        apply(c, req);
        channels.saveAndFlush(c);
        em.refresh(c);   // picks up the webhook token the database generated
        return toResponse(c);
    }

    @Override
    @Transactional
    public ChannelResponse update(UUID id, ChannelRequest req) {
        planGate.requireWritable();
        PaymentChannel c = find(id);
        apply(c, req);
        return toResponse(c);
    }

    @Override
    @Transactional
    public void deactivate(UUID id) {
        planGate.requireWritable();
        find(id).setActive(false);
    }

    /** Registers this channel's callback URLs with Safaricom. */
    @Override
    @Transactional
    public ChannelResponse registerUrls(UUID id) {
        planGate.requireWritable();
        PaymentChannel c = find(id);
        if (!isMpesa(c.getChannelType())) {
            throw ApiException.badRequest("NOT_MPESA", "Only your own M-Pesa Paybill or Till has callback URLs. "
                    + "For a bank Paybill, give the bank the notification URL shown on the channel");
        }
        daraja.registerC2bUrls(c, url(c, "validation"), url(c, "confirmation"));
        c.setUrlsRegisteredAt(OffsetDateTime.now());
        return toResponse(c);
    }

    /** Sends a payment prompt to the tenant's phone. The result arrives on the STK callback. */
    @Override
    @Transactional
    public StkPushResponse stkPush(StkPushRequest req) {
        planGate.requireWritable();
        planGate.requireFeature("STK_PUSH");
        Tenant tenant = tenants.findById(req.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        List<Lease> live = leases.findByTenantAndStatus(tenant.getId(), Lease.LIVE);
        UUID propertyId = live.isEmpty() ? null : live.getFirst().getUnit().getProperty().getId();
        String account = live.isEmpty() ? tenant.getAccountCode() : live.getFirst().getUnit().getPaymentCode();

        PaymentChannel channel = channels.findByActiveTrueOrderByCreatedAtAsc().stream()
                .filter(c -> isMpesa(c.getChannelType()))
                .filter(c -> c.getPropertyId() == null || c.getPropertyId().equals(propertyId))
                .sorted(java.util.Comparator.comparing((PaymentChannel ch) -> ch.getPropertyId() == null))  // property's own first
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("NO_MPESA", "Connect an M-Pesa Paybill in Settings first"));

        String phone = (req.phone() == null ? tenant.getPhone() : req.phone()).replace("+", "");
        DarajaClient.StkResult result = daraja.stkPush(channel, phone, req.amount().longValue(), account,
                "Rent " + account, url(channel, "stk"));

        jdbc.sql("""
                        INSERT INTO mpesa_transactions (organization_id, payment_channel_id, source, amount, business_shortcode,
                               bill_ref_number, msisdn, payer_name, checkout_request_id, merchant_request_id, raw_payload)
                        VALUES (?, ?, 'STK', ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""")
                .param(TenantContext.requireOrganizationId()).param(channel.getId()).param(req.amount())
                .param(channel.getShortcode()).param(account).param(phone).param(tenant.getName())
                .param(result.checkoutRequestId()).param(result.merchantRequestId())
                .param(toJson(Map.of("tenantId", tenant.getId().toString())))
                .update();
        return new StkPushResponse(result.checkoutRequestId(), result.customerMessage());
    }

    @Override
    public PaymentChannel find(UUID id) {
        return channels.findById(id).orElseThrow(() -> ApiException.notFound("Payment channel"));
    }

    private void apply(PaymentChannel c, ChannelRequest r) {
        boolean bankPaybill = r.channelType() == PaymentChannel.Type.BANK_PAYBILL;
        boolean bankChannel = bankPaybill || r.channelType() == PaymentChannel.Type.BANK_ACCOUNT;
        if (bankPaybill && !notBlank(r.bankCode())) {
            throw ApiException.badRequest("BANK_REQUIRED", "Choose the bank or Sacco tenants pay through");
        }
        Bank bank = bankChannel && notBlank(r.bankCode())
                ? banks.findById(r.bankCode().trim().toUpperCase(Locale.ROOT)).filter(Bank::isActive)
                        .orElseThrow(() -> ApiException.badRequest("UNKNOWN_BANK", "Unknown bank " + r.bankCode()))
                : null;
        // A bank's own Paybill (KCB 522522) unless the landlord enters one, as for a Sacco
        String shortcode = notBlank(r.shortcode()) ? r.shortcode().trim() : bankPaybill ? bank.getPaybill() : null;
        if (r.channelType() != PaymentChannel.Type.BANK_ACCOUNT && shortcode == null) {
            throw ApiException.badRequest("SHORTCODE_REQUIRED", "Enter the Paybill or Till number");
        }
        if (bankPaybill && !notBlank(r.bankAccountNo()) && c.getBankAccountNoEnc() == null) {
            throw ApiException.badRequest("BANK_ACCOUNT_REQUIRED",
                    "Enter the account number tenants pay into through the Paybill");
        }
        if (bank != null && Bank.OTHER.equals(bank.getCode()) && !notBlank(r.bankName())) {
            throw ApiException.badRequest("BANK_NAME_REQUIRED", "Enter the name of the bank or Sacco");
        }
        c.setChannelType(r.channelType());
        c.setName(r.name().trim());
        c.setShortcode(shortcode);
        c.setBankCode(bank == null ? null : bank.getCode());
        c.setPropertyId(r.propertyId());
        c.setEnvironment(r.environment() == null ? PaymentChannel.Environment.SANDBOX : r.environment());
        c.setBankName(bank != null && !notBlank(r.bankName()) ? bank.getName() : r.bankName());
        if (notBlank(r.consumerKey())) c.setConsumerKeyEnc(crypto.encrypt(r.consumerKey().trim()));
        if (notBlank(r.consumerSecret())) c.setConsumerSecretEnc(crypto.encrypt(r.consumerSecret().trim()));
        if (notBlank(r.passkey())) c.setPasskeyEnc(crypto.encrypt(r.passkey().trim()));
        if (notBlank(r.bankAccountNo())) c.setBankAccountNoEnc(crypto.encrypt(r.bankAccountNo().trim()));
    }

    private String url(PaymentChannel c, String kind) {
        return props.web().publicApiUrl().replaceAll("/+$", "") + "/api/v1/webhooks/mpesa/" + c.getWebhookToken() + "/" + kind;
    }

    private String bankNotificationUrl(PaymentChannel c) {
        return props.web().publicApiUrl().replaceAll("/+$", "") + "/api/v1/webhooks/bank/" + c.getWebhookToken() + "/notification";
    }

    /** Only banks with a connector can send us notifications; the rest are handled by statement upload. */
    private boolean hasConnector(String bankCode) {
        return bankCode != null && bankConnectors.stream().anyMatch(a -> a.bankCode().equals(bankCode));
    }

    private static boolean isMpesa(PaymentChannel.Type type) {
        return type == PaymentChannel.Type.MPESA_PAYBILL || type == PaymentChannel.Type.MPESA_TILL;
    }

    private ChannelResponse toResponse(PaymentChannel c) {
        boolean mpesa = isMpesa(c.getChannelType());
        boolean bankPaybill = c.getChannelType() == PaymentChannel.Type.BANK_PAYBILL;
        String testAccount = jdbc.sql("SELECT payment_code FROM units WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1")
                .query(String.class).optional().orElse(null);
        if (bankPaybill && testAccount != null && c.getBankCode() != null) {
            String code = testAccount;   // what tenants type on the bank's Paybill, e.g. 1234567890#KLM-B4
            testAccount = banks.findById(c.getBankCode())
                    .map(b -> b.accountFor(crypto.decrypt(c.getBankAccountNoEnc()), code)).orElse(code);
        }
        return new ChannelResponse(c.getId(), c.getChannelType().name(), c.getName(), c.getShortcode(), c.getPropertyId(),
                c.getEnvironment().name(), c.getConsumerKeyEnc() != null, c.getConsumerSecretEnc() != null,
                c.getPasskeyEnc() != null, c.getBankName(), CryptoService.mask(crypto.decrypt(c.getBankAccountNoEnc())),
                mpesa ? url(c, "validation") : null, mpesa ? url(c, "confirmation") : null, mpesa ? url(c, "stk") : null,
                c.getUrlsRegisteredAt(), c.getVerifiedAt(), c.isActive(), testAccount,
                c.getBankCode(), hasConnector(c.getBankCode()) ? bankNotificationUrl(c) : null);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}

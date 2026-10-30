package cloud.cloudonix.npg.numberingplan;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder;

import java.util.Locale;

/**
 * Google libphonenumber, applied to Israeli numbers: classification of plan ranges and parsing of
 * user-entered numbers in any common format (+972..., 972..., 0..., with separators).
 */
public final class PhoneNumbers {

    static final int ISRAEL = 972;
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
    private static final PhoneNumberOfflineGeocoder GEOCODER = PhoneNumberOfflineGeocoder.getInstance();

    /** What libphonenumber says about the numbers of one range. */
    public record RangeInfo(String numberType, String area, String exampleInternational, String exampleNational,
                            boolean valid) {
    }

    /** A parsed number. {@code nsn} is its national significant number (digits after +972). */
    public record Parsed(String input, String nsn, boolean valid, String numberType, String area,
                         String international, String national, String e164) {
    }

    private PhoneNumbers() {
    }

    /**
     * Classifies a range by sample numbers from it (lowest, middle, highest): a range is valid for
     * libphonenumber when any sample is, and takes the type of the first valid sample.
     */
    public static RangeInfo classify(String nsnPrefix, int nsnLength) {
        int free = nsnLength - nsnPrefix.length();
        PhoneNumber chosen = null;
        for (char fill : new char[]{'0', '5', '9'}) {
            PhoneNumber candidate = number(nsnPrefix + String.valueOf(fill).repeat(free));
            if (UTIL.isValidNumber(candidate)) {
                chosen = candidate;
                break;
            }
        }
        boolean valid = chosen != null;
        PhoneNumber sample = valid ? chosen : number(nsnPrefix + "0".repeat(free));
        String area = GEOCODER.getDescriptionForNumber(sample, Locale.ENGLISH);
        return new RangeInfo(UTIL.getNumberType(sample).name(), area == null || area.isBlank() ? null : area,
            UTIL.format(sample, PhoneNumberFormat.INTERNATIONAL), UTIL.format(sample, PhoneNumberFormat.NATIONAL), valid);
    }

    /** Parses a number as dialled in Israel or in international form; null when it is not a number at all. */
    public static Parsed parse(String input) {
        if (input == null || input.isBlank() || input.length() > 32) {
            return null;
        }
        try {
            PhoneNumber n = UTIL.parse(input.trim(), "IL");
            if (n.getCountryCode() != ISRAEL) {
                return null;
            }
            String area = GEOCODER.getDescriptionForNumber(n, Locale.ENGLISH);
            return new Parsed(input, UTIL.getNationalSignificantNumber(n), UTIL.isValidNumber(n), UTIL.getNumberType(n).name(),
                area == null || area.isBlank() ? null : area, UTIL.format(n, PhoneNumberFormat.INTERNATIONAL),
                UTIL.format(n, PhoneNumberFormat.NATIONAL), UTIL.format(n, PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return null;
        }
    }

    private static PhoneNumber number(String nsn) {
        return new PhoneNumber().setCountryCode(ISRAEL).setNationalNumber(Long.parseLong(nsn));
    }
}

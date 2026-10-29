# 10 - Open Questions and Assumptions

## Protocol Questions

1. Is the official transport SOAP over HTTPS, raw XML over HTTPS, or both?
2. Are machine-readable XSD/WSDL files available?
3. Is ACK sent as an HTTP response or as a separate XML message?
4. What exact HTTP status codes should be returned for ACK/NACK cases?
5. Which message fields compose the official unique key?
6. What are the final exact timer values?
7. What is the authoritative business calendar?
8. Are Ministry of Defense messages mandatory for all operators?
9. Is there currently a central clearinghouse?
10. Is FTP or SFTP required for DB synchronization files?

## Operational Questions

1. What is the required retention period for XML documents?
2. Are raw XML documents considered sensitive PII?
3. Who can access raw XML?
4. Who can perform manual transitions?
5. Should dangerous manual actions require dual approval?
6. What are the NOC escalation paths?
7. What operator endpoint/certificate data is available?

## Implementation Assumptions

Until answered:

* Implement transport through an adapter interface.
* Use HTTPS raw XML endpoint by default.
* Support SOAP envelope parsing as a future adapter.
* Store all timer values in configuration tables.
* Store message catalog in YAML.
* Make operator endpoints configurable.
* Make retry policies configurable.
* Make working-calendar logic pluggable.

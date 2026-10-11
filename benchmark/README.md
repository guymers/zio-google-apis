`benchmark/Jmh/run -i 10 -wi 5 -f 1 -t 1 zga.benchmark.SearchGoogleAdsResponseBenchmark`

Encoding and decoding a page of 1000 Google Ads search results, compared against the protobuf-java messages of `google-ads-stubs-v25`.

AMD Ryzen 9 9950X
```
# JMH version: 1.37
# VM version: JDK 25.0.4.1, OpenJDK 64-Bit Server VM, 25.0.4.1
Benchmark                                    Mode  Cnt     Score    Error  Units
SearchGoogleAdsResponseBenchmark.decode      avgt   10  1128.528 ±  3.223  us/op
SearchGoogleAdsResponseBenchmark.decodeJava  avgt   10  1044.894 ±  6.294  us/op
SearchGoogleAdsResponseBenchmark.encode      avgt   10  1063.379 ±  1.618  us/op
SearchGoogleAdsResponseBenchmark.encodeJava  avgt   10  1628.386 ± 24.389  us/op
```

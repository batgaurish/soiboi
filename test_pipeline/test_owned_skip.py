from soiboi_pipeline.downloader import DownloadRequest, owned_key


def test_owned_key_ignores_case_and_punctuation():
    assert owned_key("Rex Orange County", "Corduroy Dreams") == owned_key(
        "rex orange county", "Corduroy  Dreams!"
    )
    assert owned_key("A & B", "x") == owned_key("A and B", "x")


def test_owned_key_keeps_non_latin_titles_apart():
    assert owned_key("Arpit Bala", "तारों से") != owned_key("Arpit Bala", "मन")
    assert owned_key("Arpit Bala", "तारों से") != "arpitbala|"


def test_payload_carries_owned_keys():
    request = DownloadRequest.from_payload(
        {"url": "u", "cookies_path": "c", "output_dir": "o", "owned": ["a|b"]}
    )
    assert "a|b" in request.owned

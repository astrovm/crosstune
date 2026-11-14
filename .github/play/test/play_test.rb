# play.sh against a fake Google Play (fake_play.rb), through the real fastlane supply that Gemfile.lock pins.
# Every commit has to ask Play to keep a review in progress, a commit Play refuses for one has to stop the job
# with a plain message, and production never shares an edit with another track.
#
#   BUNDLE_GEMFILE=.github/play/Gemfile BUNDLE_WITHOUT= bundle install
#   BUNDLE_GEMFILE=.github/play/Gemfile BUNDLE_WITHOUT= bundle exec ruby .github/play/test/play_test.rb
require "minitest/autorun"
require "json"
require "open3"
require "openssl"
require "tmpdir"
require "fileutils"

class PlayTest < Minitest::Test
  PLAY_SH = File.expand_path("../play.sh", __dir__)
  PLAY_RB = File.expand_path("../play.rb", __dir__)
  FAKE_PLAY = File.expand_path("fake_play.rb", __dir__)
  PACKAGE = "com.astrovm.crosstune"
  TAG = "v2.26.0"
  CODE = 2_260_000
  KEEP_REVIEW = { "changesInReviewBehavior" => "ERROR_IF_IN_REVIEW" }.freeze
  IN_REVIEW = "Google Play is still reviewing changes sent before, so this job sent nothing to Google Play, " \
              "and the review goes on. Re-run this failed job when the review ends."
  # A key no Google account has: fake_play.rb answers the token request itself.
  KEY = JSON.generate(
    type: "service_account",
    project_id: "fake-project",
    private_key_id: "fake",
    private_key: OpenSSL::PKey::RSA.new(2048).to_pem,
    client_email: "release@fake-project.iam.gserviceaccount.com",
    client_id: "1",
    token_uri: "https://oauth2.googleapis.com/token"
  )

  def setup
    @dir = Dir.mktmpdir("play-test")
    metadata = File.join(@dir, "fastlane/metadata/android")
    %w[en-US es].each do |language|
      folder = File.join(metadata, language)
      FileUtils.mkdir_p(File.join(folder, "images"))
      File.write(File.join(folder, "title.txt"), "Fake app")
      File.write(File.join(folder, "short_description.txt"), "A fake listing")
      File.write(File.join(folder, "full_description.txt"), "A fake listing, for the tests.")
      File.binwrite(File.join(folder, "images/icon.png"), "fake png #{language}")
    end
    FileUtils.mkdir_p(File.join(metadata, "en-US/changelogs"))
    File.write(File.join(metadata, "en-US/changelogs/#{CODE}.txt"), "Fake notes")
    File.binwrite(File.join(@dir, "Crosstune-#{TAG}.aab"), "fake bundle")
    @log = File.join(@dir, "requests.jsonl")
    @state = File.join(@dir, "play.json")
    File.write(@log, "")
    tracks({})
  end

  def teardown
    FileUtils.rm_rf(@dir)
  end

  def tracks(tracks)
    File.write(@state, JSON.generate(tracks: tracks))
  end

  # The releases on each track, as Play keeps them. A field sent empty is no field.
  def saved
    JSON.parse(File.read(@state))["tracks"].transform_values { |releases| releases.map(&:compact) }
  end

  def release(code, notes = [])
    [{ "versionCodes" => [code.to_s], "status" => "completed", "releaseNotes" => notes }]
  end

  def env(extra)
    {
      "PLAY_SERVICE_ACCOUNT_JSON" => KEY,
      "PLAY_TRACKS" => nil,
      "PLAY_LANGUAGES" => nil,
      "CHECK_ONLY" => nil,
      "GITHUB_ACTIONS" => "true",
      "RUNNER_TEMP" => @dir,
      # The test group too, for WebMock.
      "BUNDLE_WITHOUT" => "",
      "RUBYOPT" => "-r#{FAKE_PLAY}",
      "FAKE_PLAY_LOG" => @log,
      "FAKE_PLAY_STATE" => @state,
      "FAKE_PLAY_VERSION_CODE" => CODE.to_s
    }.merge(extra)
  end

  def play(*args, env: {})
    Open3.capture2e(env(env), "bash", PLAY_SH, *args, chdir: @dir)
  end

  def requests
    File.readlines(@log).map { |line| JSON.parse(line) }
  end

  def edit_of(request)
    request["path"][%r{/edits/([^/:]+)}, 1]
  end

  def commits
    requests.select { |r| r["method"] == "POST" && r["path"].end_with?(":commit") }
  end

  # The tracks each committed edit changed, in the order of the commits.
  def committed_tracks
    commits.map do |commit|
      requests.select { |r| %w[PUT PATCH].include?(r["method"]) && edit_of(r) == edit_of(commit) }
              .filter_map { |r| r["path"][%r{/tracks/([^/]+)\z}, 1] }.uniq
    end
  end

  def listings
    requests.select { |r| %w[PUT PATCH].include?(r["method"]) && r["path"].include?("/listings/") }
  end

  def assert_every_commit_keeps_the_review
    refute_empty commits
    commits.each { |commit| assert_equal KEEP_REVIEW, commit["query"].slice("changesInReviewBehavior") }
  end

  def refute_production_mixed
    committed_tracks.each do |changed|
      refute(changed.include?("production") && changed.size > 1, "an edit mixed production: #{changed}")
    end
  end

  def test_a_release_goes_to_internal_testing_then_open_testing_with_the_listing
    output, status = play("release", TAG)
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [["internal"], ["beta"]], committed_tracks
    upload, promotion = commits
    assert(requests.any? { |r| r["upload"] && r["path"].end_with?("/bundles") && edit_of(r) == edit_of(upload) })
    # The listing goes in the promotion's edit, so Google reviews both at once.
    assert_equal ["en-US"], listings.map { |r| r["path"].split("/").last }
    assert_equal [edit_of(promotion)], listings.map { |r| edit_of(r) }.uniq
    notes = [{ "language" => "en-US", "text" => "Fake notes" }]
    assert_equal release(CODE, notes), saved["beta"]
    assert_nil saved["production"]
  end

  def test_a_release_goes_to_every_testing_track_named_in_one_edit
    output, status = play("release", TAG, env: { "PLAY_TRACKS" => "beta,alpha" })
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [["internal"], %w[beta alpha]], committed_tracks
    assert_equal [edit_of(commits.last)], listings.map { |r| edit_of(r) }.uniq
    assert_equal saved["beta"], saved["alpha"]
    assert_nil saved["production"]
  end

  def test_a_release_never_names_production_or_internal_testing
    %w[production beta,production internal beta,tv:production wear:internal].each do |named|
      output, status = play("release", TAG, env: { "PLAY_TRACKS" => named })
      refute status.success?, output
      assert_includes output, "PLAY_TRACKS can't hold"
      assert_empty requests
    end
  end

  def test_a_release_sends_the_languages_named
    output, status = play("release", TAG, env: { "PLAY_LANGUAGES" => "en-US es" })
    assert status.success?, output

    assert_equal %w[en-US es-419], listings.map { |r| r["path"].split("/").last }.sort
  end

  def test_a_rerun_doesnt_send_the_bundle_again
    tracks("internal" => release(CODE))
    output, status = play("release", TAG)
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [["beta"]], committed_tracks
    refute(requests.any? { |r| r["path"].end_with?("/bundles") })
    assert_includes output, "Internal testing already has #{CODE}"
  end

  def test_production_goes_on_its_own_with_the_english_notes
    tracks("beta" => release(CODE), "alpha" => release(CODE))
    output, status = play("production", "2.26.0", env: { "PLAY_TRACKS" => "beta,alpha" })
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [["production"]], committed_tracks
    assert_empty listings
    assert_equal release(CODE, [{ "language" => "en-US", "text" => "Fake notes" }]), saved["production"]
    assert_equal release(CODE), saved["beta"]
  end

  def play_rb(*args)
    key = File.join(@dir, "key.json")
    File.write(key, KEY)
    Open3.capture2e(env({}), "bundle", "exec", "ruby", PLAY_RB, args[0], key, PACKAGE, *args[1..], chdir: @dir)
  end

  def test_production_and_a_testing_track_never_share_an_edit
    { "production,alpha" => "production and alpha", "beta,tv:production" => "tv:production and beta" }
      .each do |to, changed|
        File.write(@log, "")
        tracks("beta" => release(CODE))
        output, status = play_rb("edit", "--from", "beta", "--code", CODE.to_s, "--to", to)
        refute status.success?, output

        assert_includes output, "This edit changed #{changed}, so it was deleted, and nothing was sent"
        assert_empty commits
        assert_equal "DELETE", requests.last["method"]
        assert_equal({ "beta" => release(CODE) }, saved)
      end
  end

  def test_production_tracks_of_two_form_factors_can_share_an_edit
    tracks("beta" => release(CODE))
    output, status = play_rb("edit", "--from", "beta", "--code", CODE.to_s, "--to", "production,tv:production")
    assert status.success?, output

    assert_equal [%w[production tv:production]], committed_tracks
  end

  def test_a_draft_with_no_version_codes_is_passed_over
    tracks("internal" => [{ "status" => "draft" }] + release(CODE))
    output, status = play("release", TAG)
    assert status.success?, output

    assert_equal [["beta"]], committed_tracks
    assert_equal release(CODE), saved["beta"]
  end

  def test_a_release_not_on_the_track_stops_plainly
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "[!] Track 'beta' has no release with version code #{CODE}"
    assert_empty commits
  end

  def test_a_tag_with_a_suffix_keeps_its_version_code
    FileUtils.mv(File.join(@dir, "Crosstune-#{TAG}.aab"), File.join(@dir, "Crosstune-#{TAG}-rc.1.aab"))
    output, status = play("release", "#{TAG}-rc.1")
    assert status.success?, output

    assert_equal [CODE.to_s], saved["beta"].flat_map { |r| r["versionCodes"] }
  end

  def test_the_listing_goes_on_its_own
    output, status = play("listing")
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [[]], committed_tracks
    assert_equal ["en-US"], listings.map { |r| r["path"].split("/").last }
  end

  def test_a_listing_check_commits_nothing
    output, status = play("listing", env: { "CHECK_ONLY" => "true" })
    assert status.success?, output

    assert_empty commits
    assert(requests.any? { |r| r["path"].end_with?(":validate") })
  end

  def test_a_review_in_progress_stops_each_job_with_a_plain_message
    tracks("beta" => release(CODE))
    [%w[release] + [TAG], %w[production 2.26.0], %w[listing]].each do |args|
      File.write(@log, "")
      output, status = play(*args, env: { "FAKE_PLAY_IN_REVIEW" => "1" })
      refute status.success?, output

      assert_includes output, "::error title=Google Play is reviewing the app::#{IN_REVIEW}"
      assert_includes output, "[!] #{IN_REVIEW}"
      # Play keeps the refused edit, so it's deleted. Nothing else was tried after it.
      assert_equal 1, commits.size
      assert_every_commit_keeps_the_review
      assert_equal ["DELETE", edit_of(commits.first)], [requests.last["method"], edit_of(requests.last)]
      assert_equal({ "beta" => release(CODE) }, saved)
    end
  end
end

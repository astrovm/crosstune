# play.sh against a fake Google Play (fake_play.rb), through the real fastlane supply that Gemfile.lock pins.
# Every commit has to ask Play to keep a review in progress, a commit Play refuses for one has to stop the job
# with a plain message, and production never shares an edit with another track.
#
#   BUNDLE_WITHOUT= .github/play/play.sh setup
#   BUNDLE_GEMFILE=.github/play/Gemfile BUNDLE_WITHOUT= bundle exec ruby .github/play/test/play_test.rb
require "minitest/autorun"
require "digest"
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
    # The repository the job checks out, with its tags on origin, where play.sh lists them.
    @origin = Dir.mktmpdir("play-origin")
    git(@origin, "init", "-q")
    git(@origin, "commit", "-q", "--allow-empty", "-m", "Fake")
    # 2.25.0 is on some tracks in the tests below.
    tags("v2.25.0", TAG)
    # The GitHub releases, with their assets, where promote.yml downloads a release's App Bundle.
    @releases = File.join(@dir, "releases")
    git(@dir, "init", "-q")
    git(@dir, "remote", "add", "origin", @origin)
    @log = File.join(@dir, "requests.jsonl")
    @state = File.join(@dir, "play.json")
    File.write(@log, "")
    tracks({})
  end

  def teardown
    FileUtils.rm_rf(@dir)
    FileUtils.rm_rf(@origin)
  end

  def git(dir, *args)
    output, status = Open3.capture2e("git", "-C", dir, "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                                     *args)
    assert status.success?, output
  end

  def tags(*names)
    names.each { |name| git(@origin, "tag", name) }
  end

  # The App Bundle a GitHub release keeps as an asset.
  def asset(tag = TAG, content = "fake bundle")
    FileUtils.mkdir_p(File.join(@releases, tag))
    File.binwrite(File.join(@releases, tag, "Crosstune-#{tag}.aab"), content)
  end

  def tracks(tracks, bundles = [])
    File.write(@state, JSON.generate(tracks: tracks, bundles: bundles))
  end

  def bundle(content = "fake bundle")
    { "versionCode" => CODE, "sha256" => Digest::SHA256.hexdigest(content) }
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
      "FAKE_PLAY_VERSION_CODE" => CODE.to_s,
      "RELEASES_URL" => "file://#{@releases}"
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
    tracks({ "internal" => release(CODE) }, [bundle])
    output, status = play("release", TAG)
    assert status.success?, output

    assert_every_commit_keeps_the_review
    assert_equal [["beta"]], committed_tracks
    refute(requests.any? { |r| r["method"] == "POST" && r["path"].end_with?("/bundles") })
    assert_includes output, "Internal testing already has this bundle as #{CODE}"
  end

  def test_another_bundle_with_the_same_version_code_stops_the_release
    tracks({ "internal" => release(CODE), "beta" => release(CODE) }, [bundle("another bundle")])
    output, status = play("release", TAG)
    refute status.success?, output

    assert_includes output, "[!] Google Play already has another bundle as version code #{CODE}, and it takes each " \
                            "version code once. Release this as a new version"
    assert_empty commits
    refute(requests.any? { |r| r["method"] == "POST" && r["path"].end_with?("/bundles") })
  end

  def test_a_rerun_after_a_refused_commit_puts_the_bundle_play_kept_on_internal_testing
    # Play may keep a bundle sent in an edit it then refused, so the bundle is in the app, on no track.
    kept = { "FAKE_PLAY_KEEPS_UPLOADS" => "1" }
    output, status = play("release", TAG, env: kept.merge("FAKE_PLAY_IN_REVIEW" => "1"))
    refute status.success?, output
    assert_includes output, IN_REVIEW
    assert_equal [bundle], JSON.parse(File.read(@state))["bundles"]
    assert_equal({}, saved)

    File.write(@log, "")
    output, status = play("release", TAG, env: kept)
    assert status.success?, output

    assert_includes output, "Google Play has this bundle as #{CODE} from an earlier run, and no testing track has a " \
                            "newer version, so it isn't sent again"
    refute(requests.any? { |r| r["method"] == "POST" && r["path"].end_with?("/bundles") })
    assert_every_commit_keeps_the_review
    assert_equal [["internal"], ["beta"]], committed_tracks
    notes = [{ "language" => "en-US", "text" => "Fake notes" }]
    assert_equal release(CODE, notes), saved["internal"]
    assert_equal release(CODE, notes), saved["beta"]
  end

  NEWER = 2_270_000

  # What changes Play: anything but reading, and opening or deleting an edit.
  def writes
    requests.reject { |r| r["method"] == "GET" || r["path"].end_with?("/edits") || r["method"] == "DELETE" }
  end

  def newer_bundle
    { "versionCode" => NEWER, "sha256" => Digest::SHA256.hexdigest("newer") }
  end

  def uploads
    requests.select { |r| r["method"] == "POST" && r["path"].end_with?("/bundles") }
  end

  def test_a_rerun_of_an_older_tag_leaves_the_newer_version_on_play
    # v2.26.0 again, after v2.27.0 went out: 2.26.0 is in production, and 2.27.0 on the testing tracks.
    tags("v2.27.0")
    play_had = { "internal" => release(NEWER), "beta" => release(NEWER), "production" => release(CODE) }
    tracks(play_had, [bundle, newer_bundle])
    output, status = play("release", TAG)
    assert status.success?, output

    assert_includes output, "Google Play has a version newer than #{CODE} on internal (#{NEWER}), beta (#{NEWER})"
    assert_includes output, "::notice::#{TAG} stays on GitHub only: Google Play has a newer version, and production " \
                            "has this one or a newer one"
    assert_empty writes
    assert_equal play_had, saved
  end

  def test_a_backport_sends_nothing_to_play_and_says_how_to_reach_production
    # v2.26.0 for the first time after v2.27.0, which only one of the testing tracks has. Production has 2.25.0.
    tags("v2.27.0")
    older = CODE - 10_000
    play_had = { "internal" => release(older), "beta" => release(older), "alpha" => release(NEWER),
                 "production" => release(older) }
    tracks(play_had, [newer_bundle])
    output, status = play("release", TAG, env: { "PLAY_TRACKS" => "beta,alpha" })
    assert status.success?, output

    assert_includes output, "Google Play has a version newer than #{CODE} on alpha (#{NEWER})"
    assert_includes output, "::notice::#{TAG} isn't sent to Google Play, whose testing tracks have a newer version. " \
                            "To send it to production, run promote.yml with 2.26.0"
    assert_empty writes
    assert_equal play_had, saved
  end

  def test_a_backport_stays_on_github_only_when_production_has_a_newer_version
    tags("v2.27.0")
    older = CODE - 10_000
    play_had = { "internal" => release(older), "beta" => release(older), "production" => release(NEWER) }
    tracks(play_had, [newer_bundle])
    output, status = play("release", TAG)
    assert status.success?, output

    assert_includes output, "Google Play has a version newer than #{CODE} on production (#{NEWER})"
    assert_includes output, "::notice::#{TAG} stays on GitHub only"
    assert_empty writes
    assert_equal play_had, saved
  end

  def test_a_version_code_no_tag_has_stops_every_release
    # A bundle sent by hand, a draft in Play Console, or a tag pushed and then deleted, on any track read.
    stray = 3_000_000
    { "internal" => %w[beta], "alpha" => %w[beta alpha], "production" => %w[beta] }.each do |track, testing|
      [[], ["v2.27.0"]].each do |more|
        File.write(@log, "")
        tags(*more)
        older = CODE - 10_000
        play_had = { "internal" => release(older), "beta" => release(older), "production" => release(older) }
                   .merge(track => release(stray))
        tracks(play_had)
        output, status = play("release", TAG, env: { "PLAY_TRACKS" => testing.join(",") })
        refute status.success?, output

        assert_includes output, "::error::Google Play has version code #{stray} on #{track}, and no tag has it"
        assert_empty writes
        assert_equal play_had, saved
      end
      git(@origin, "tag", "-d", "v2.27.0")
    end
  end

  def test_a_version_code_no_tag_has_stops_the_release_below_the_highest_tag_too
    # A bundle sent by hand between two versions, and a tag that never reached Play, such as one whose build failed.
    older = CODE - 10_000
    [[["v2.27.0"], CODE + 500], [["v3.0.0"], 2_990_000], [[], CODE - 500]].each do |more, stray|
      File.write(@log, "")
      tags(*more)
      play_had = { "internal" => release(stray), "beta" => release(older), "production" => release(older) }
      tracks(play_had)
      output, status = play("release", TAG)
      refute status.success?, output

      assert_includes output, "::error::Google Play has version code #{stray} on internal, and no tag has it"
      assert_empty writes
      assert_equal play_had, saved
      more.each { |tag| git(@origin, "tag", "-d", tag) }
    end
  end

  def test_a_rerun_after_a_refused_commit_goes_on_when_nothing_newer_is_on_play
    # The commit was refused, Play kept the bundle, and the tracks still have the version before.
    older = CODE - 10_000
    tracks({ "internal" => release(older), "beta" => release(older), "alpha" => release(older),
             "production" => release(older) }, [bundle])
    output, status = play("release", TAG, env: { "PLAY_TRACKS" => "beta,alpha" })
    assert status.success?, output

    assert_includes output, "Google Play has this bundle as #{CODE} from an earlier run, and no testing track has a " \
                            "newer version, so it isn't sent again"
    refute(requests.any? { |r| r["method"] == "POST" && r["path"].end_with?("/bundles") })
    assert_equal [["internal"], %w[beta alpha]], committed_tracks
    assert_equal [CODE.to_s], saved["alpha"].flat_map { |r| r["versionCodes"] }
  end

  def test_production_never_goes_back_to_an_older_version
    play_had = { "beta" => release(CODE), "production" => release(NEWER) }
    tracks(play_had)
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "[!] Google Play already has a version newer than #{CODE} on production (#{NEWER}), " \
                            "so nothing was sent: a release never takes a track back to an older version"
    assert_empty writes
    assert_equal play_had, saved
  end

  def test_a_backport_goes_to_production_from_its_github_release
    # Testing has 2.27.0, production 2.25.0, and Play has no 2.26.0: its release sent nothing.
    play_had = { "beta" => release(NEWER), "production" => release(CODE - 10_000) }
    tracks(play_had, [newer_bundle])
    asset
    output, status = play("production", "2.26.0")
    assert status.success?, output

    assert_includes output, "Track 'beta' has a newer version than #{CODE}"
    assert_includes output, "So production gets #{TAG}'s App Bundle from its GitHub release"
    refute_empty uploads
    assert_every_commit_keeps_the_review
    assert_equal [["production"]], committed_tracks
    assert_equal release(CODE, [{ "language" => "en-US", "text" => "Fake notes" }]), saved["production"]
    assert_equal release(NEWER), saved["beta"]
    assert_equal [newer_bundle, bundle], JSON.parse(File.read(@state))["bundles"]

    # Play has it already, from an earlier run whose commit it refused: it isn't sent again.
    File.write(@log, "")
    tracks(play_had, [newer_bundle, bundle])
    output, status = play("production", "2.26.0")
    assert status.success?, output
    assert_empty uploads
    assert_equal [["production"]], committed_tracks
  end

  def test_promote_refuses_a_version_beta_never_had
    # v2.26.0 reached internal testing, and the edit that sends it on to beta failed.
    play_had = { "internal" => release(CODE), "beta" => release(CODE - 10_000), "production" => release(CODE - 10_000) }
    tracks(play_had, [bundle])
    asset
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "Track 'beta' has no release with version code #{CODE}"
    assert_empty writes
    assert_equal play_had, saved
  end

  def test_production_takes_a_github_release_only_over_an_older_version
    [[{ "beta" => release(NEWER), "production" => release(CODE) }, [bundle], true,
      "[!] production has #{CODE} already, so nothing was sent"],
     [{ "beta" => release(NEWER), "production" => release(CODE - 10_000) }, [bundle("another bundle")], true,
      "[!] Google Play already has another bundle as version code #{CODE}"],
     [{ "beta" => release(NEWER), "production" => release(CODE - 10_000) }, [], false,
      "::error::#{TAG}'s GitHub release has no Crosstune-#{TAG}.aab, so nothing was sent"]]
      .each do |play_had, bundles, with_asset, message|
        File.write(@log, "")
        FileUtils.rm_rf(@releases)
        asset if with_asset
        tracks(play_had, bundles)
        output, status = play("production", "2.26.0")
        refute status.success?, output

        assert_includes output, message
        assert_empty writes
        assert_equal play_had, saved
      end
  end

  def test_promote_names_the_release_notes_it_misses
    File.delete(File.join(@dir, "fastlane/metadata/android/en-US/changelogs/#{CODE}.txt"))
    tracks("beta" => release(CODE))
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "::error::fastlane/metadata/android/en-US/changelogs/#{CODE}.txt isn't here"
    assert_empty requests
  end

  def test_an_edit_is_deleted_whatever_stops_it
    # Play fails to list the tracks, in each command that reads them.
    tracks("beta" => release(CODE))
    [->(failing) { play("release", TAG, env: failing) }, ->(failing) { play("production", "2.26.0", env: failing) },
     ->(failing) { play_rb("edit", "--from", "beta", "--code", CODE.to_s, "--to", "production", env: failing) }]
      .each do |run|
      File.write(@log, "")
      output, status = run.call({ "FAKE_PLAY_TRACKS_FAIL" => "1" })
      refute status.success?, output

      assert_includes output, "Backend Error"
      opened = requests.select { |r| r["method"] == "POST" && r["path"].end_with?("/edits") }
      deleted = requests.select { |r| r["method"] == "DELETE" }
      refute_empty opened
      assert_equal opened.size, deleted.size
      assert_empty writes
    end
  end

  def test_a_version_code_goes_with_a_track
    listing = File.join(@dir, "fastlane/metadata/android")
    output, status = play_rb("edit", "--code", CODE.to_s, "--listing", listing)
    refute status.success?, output

    assert_includes output, "Name --to with --code"
    assert_empty requests
  end

  def test_the_play_tools_keep_to_120_columns
    Dir[File.expand_path("../{,test/}*.{rb,sh}", __dir__)].each do |file|
      File.readlines(file).each_with_index do |line, number|
        assert_operator line.chomp.length, :<=, 120, "#{File.basename(file)}:#{number + 1}"
      end
    end
  end

  def test_the_same_bundle_is_found_whatever_case_play_writes_its_sha256_in
    tracks({ "internal" => release(CODE) }, [bundle.merge("sha256" => bundle["sha256"].upcase)])
    output, status = play("release", TAG)
    assert status.success?, output

    assert_includes output, "Internal testing already has this bundle as #{CODE}"
  end

  def test_fastlanes_own_commit_retry_keeps_the_review_too
    # Play asks for changesNotSentForReview, and supply commits again with it.
    output, status = play("release", TAG, env: { "FAKE_PLAY_NOT_SENT" => "1" })
    assert status.success?, output

    assert_every_commit_keeps_the_review
    # supply's own commit, then its retry, in each of the two edits.
    assert_equal %w[false true false true], commits.map { |c| c["query"]["changesNotSentForReview"] }
    assert_equal release(CODE, [{ "language" => "en-US", "text" => "Fake notes" }]), saved["beta"]
  end

  def test_a_review_in_progress_stops_fastlanes_own_retry_with_the_plain_message
    output, status = play("release", TAG, env: { "FAKE_PLAY_NOT_SENT" => "1", "FAKE_PLAY_IN_REVIEW" => "1" })
    refute status.success?, output

    assert_includes output, "::error title=Google Play is reviewing the app::#{IN_REVIEW}"
    assert_includes output, "[!] #{IN_REVIEW}"
    assert_every_commit_keeps_the_review
    assert_equal %w[false true], commits.map { |c| c["query"]["changesNotSentForReview"] }
    assert_equal ["DELETE", edit_of(commits.last)], [requests.last["method"], edit_of(requests.last)]
    assert_equal({}, saved)
  end

  def test_setup_needs_no_key_and_a_job_never_installs_where_the_key_is
    output, status = play("setup", env: { "PLAY_SERVICE_ACCOUNT_JSON" => nil })
    assert status.success?, output
    output, status = play("setup")
    refute status.success?, output
    assert_includes output, "::error::play.sh setup installs gems, which run their own code, so it never runs " \
                            "with the Play key"

    empty = File.join(@dir, "no-gems")
    FileUtils.mkdir_p(empty)
    output, status = play("release", TAG, env: { "BUNDLE_PATH" => empty })
    refute status.success?, output
    assert_includes output, "::error::The gems aren't installed. Run play.sh setup first, without the key"
    assert_empty Dir.children(empty)
    assert_empty requests
  end

  def test_the_bundle_sent_is_the_one_play_keeps
    output, status = play("release", TAG)
    assert status.success?, output

    assert_equal [bundle], JSON.parse(File.read(@state))["bundles"]
  end

  def test_a_version_code_in_two_releases_stops_the_promotion
    tracks("beta" => release(CODE) + release(CODE))
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "[!] Track 'beta' has 2 releases with version code #{CODE}"
    assert_empty commits
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

  def play_rb(*args, env: {})
    key = File.join(@dir, "key.json")
    File.write(key, KEY)
    Open3.capture2e(env(env), "bundle", "exec", "ruby", PLAY_RB, args[0], key, PACKAGE, *args[1..], chdir: @dir)
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
    tracks({ "internal" => [{ "status" => "draft" }] + release(CODE) }, [bundle])
    output, status = play("release", TAG)
    assert status.success?, output

    assert_equal [["beta"]], committed_tracks
    assert_equal release(CODE), saved["beta"]
  end

  def test_a_release_not_on_the_track_stops_plainly
    output, status = play("production", "2.26.0")
    refute status.success?, output

    assert_includes output, "Track 'beta' has no release with version code #{CODE}"
    assert_empty commits
  end

  def test_a_tag_with_a_suffix_isnt_sent_to_play
    # No bundle and no key: the tag alone decides.
    FileUtils.rm(File.join(@dir, "Crosstune-#{TAG}.aab"))
    ["#{TAG}-rc.1", "#{TAG}.1"].each do |tag|
      output, status = play("release", tag, env: { "PLAY_SERVICE_ACCOUNT_JSON" => nil })
      assert status.success?, output

      assert_includes output, "::notice::#{tag} has a suffix, so it isn't sent to Google Play"
    end
    assert_empty requests
  end

  def test_a_release_stops_on_a_tag_that_isnt_a_version
    ["v2.26", "#{TAG}-"].each do |tag|
      output, status = play("release", tag)
      refute status.success?, output

      assert_includes output, "::error::'#{tag}' must look like 2.26.0 or v2.26.0"
    end
    assert_empty requests
  end

  def test_production_takes_a_plain_version_only
    tracks("beta" => release(CODE))
    output, status = play("production", "2.26.0-rc.1")
    refute status.success?, output

    assert_includes output, "::error::'2.26.0-rc.1' must look like 2.26.0 or v2.26.0"
    assert_empty requests

    output, status = play("production", TAG)
    assert status.success?, output
    assert_equal [["production"]], committed_tracks
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

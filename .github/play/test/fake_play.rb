# A fake Google Play for play_test.rb, loaded through RUBYOPT into each `ruby play.rb` that play.sh starts.
# WebMock answers every request to Google, so nothing leaves the machine. Each request is written to
# FAKE_PLAY_LOG, one JSON line each, and the tracks a commit keeps are in FAKE_PLAY_STATE, so they last from
# one fastlane run to the next. FAKE_PLAY_IN_REVIEW=1 makes Play refuse commits as it does while a review is
# in progress. FAKE_PLAY_NOT_SENT=1 makes it ask for changesNotSentForReview first, as it does for some
# changes. FAKE_PLAY_KEEPS_UPLOADS=1 makes it keep a bundle once sent, even when its edit is never committed.
#
# RUBYOPT reaches `bundle` too, which has no WebMock: only play.rb gets the fake.
return unless File.basename($PROGRAM_NAME) == "play.rb"

require "digest"
require "json"
require "webmock"

module FakePlay
  extend WebMock::API

  API = %r{\Ahttps://androidpublisher\.googleapis\.com/(?:upload/)?androidpublisher/v3/applications/([^/]+)/edits}
  LOG = ENV.fetch("FAKE_PLAY_LOG")
  STATE = ENV.fetch("FAKE_PLAY_STATE")
  # The sample Play's documentation gives for a commit refused while a review is in progress.
  # What Play answers a commit of changes it doesn't send to review by itself.
  NOT_SENT = {
    error: {
      code: 400,
      message: "Changes cannot be sent for review automatically. Please set the query parameter " \
               "changesNotSentForReview to true. Once committed, the changes in this edit can be sent for " \
               "review from the Google Play Console UI.",
      status: "INVALID_ARGUMENT"
    }
  }.freeze
  IN_REVIEW = {
    error: {
      code: 400,
      message: "You already have changes in review. Cancel this review or wait for it to complete before you " \
               "try again. See https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases",
      status: "FAILED_PRECONDITION",
      details: [{
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        reason: "CHANGES_ALREADY_IN_REVIEW",
        domain: "googleapis.com",
        metadata: { editId: "123456790", method: "edits.commit", service: "androidpublisher.googleapis.com" }
      }]
    }
  }.freeze

  @edits = {}

  class << self
    # What Play keeps: the tracks, and the bundles with their SHA-256.
    def saved
      { "tracks" => {}, "bundles" => [] }.merge(JSON.parse(File.read(STATE)))
    end

    def log(request)
      uri = request.uri
      entry = {
        method: request.method.to_s.upcase,
        path: uri.path,
        query: uri.query_values || {},
        upload: request.headers["X-Goog-Upload-Protocol"] == "resumable"
      }
      File.open(LOG, "a") { |file| file.puts(JSON.generate(entry)) }
    end

    def json(body, status = 200, headers = {})
      { status: status, body: JSON.generate(body), headers: { "Content-Type" => "application/json" }.merge(headers) }
    end

    def track_json(name, releases)
      { track: name, releases: releases }
    end

    def answer(request)
      log(request)
      path = request.uri.path.sub(%r{\A/(?:upload/)?androidpublisher/v3/applications/[^/]+/edits}, "")
      method = request.method
      upload = { "X-Goog-Upload-Status" => "final" }
      case [method, path]
      in [:post, ""]
        id = "edit-#{Process.pid}-#{@edits.size + 1}"
        @edits[id] = saved
        json({ id: id })
      in [:delete, %r{\A/([^/:]+)\z}]
        @edits.delete(Regexp.last_match(1))
        { status: 204, body: "" }
      in [:post, %r{\A/([^/]+):commit\z}]
        id = Regexp.last_match(1)
        not_sent = request.uri.query_values.to_h["changesNotSentForReview"] == "true"
        return json(NOT_SENT, 400) if ENV["FAKE_PLAY_NOT_SENT"] == "1" && !not_sent
        return json(IN_REVIEW, 400) if ENV["FAKE_PLAY_IN_REVIEW"] == "1"

        File.write(STATE, JSON.generate(@edits.delete(id)))
        json({ id: id })
      in [:post, %r{\A/([^/]+):validate\z}]
        json({ id: Regexp.last_match(1) })
      in [:get, %r{\A/([^/]+)/tracks\z}]
        edit = @edits.fetch(Regexp.last_match(1))["tracks"]
        json({ tracks: edit.map { |name, releases| track_json(name, releases) } })
      in [:get, %r{\A/([^/]+)/tracks/([^/]+)\z}]
        releases = @edits.fetch(Regexp.last_match(1))["tracks"][Regexp.last_match(2)]
        # What Play answers for a track with no release yet.
        releases ? json(track_json(Regexp.last_match(2), releases)) : json({ error: { code: 404, message: "Track not found." } }, 404)
      in [:put, %r{\A/([^/]+)/tracks/([^/]+)\z}]
        edit = @edits.fetch(Regexp.last_match(1))["tracks"]
        name = Regexp.last_match(2)
        # Play keeps version codes as strings, whatever was sent.
        releases = JSON.parse(request.body)["releases"].map do |release|
          release.merge("versionCodes" => release["versionCodes"]&.map(&:to_s))
        end
        edit[name] = releases
        json(track_json(name, releases))
      in [:get, %r{\A/([^/]+)/bundles\z}]
        json({ bundles: @edits.fetch(Regexp.last_match(1))["bundles"] })
      in [:post, %r{\A/([^/]+)/bundles\z}]
        # Google's client starts the upload, then sends the bundle.
        return { status: 200, body: "", headers: { "X-Goog-Upload-Status" => "active", "X-Goog-Upload-URL" => request.uri.to_s } } \
          if request.headers["X-Goog-Upload-Command"] == "start"

        bundle = { "versionCode" => Integer(ENV.fetch("FAKE_PLAY_VERSION_CODE")), "sha256" => Digest::SHA256.hexdigest(request.body) }
        @edits.fetch(Regexp.last_match(1))["bundles"] << bundle
        File.write(STATE, JSON.generate(saved.merge("bundles" => saved["bundles"] + [bundle]))) if ENV["FAKE_PLAY_KEEPS_UPLOADS"] == "1"
        json(bundle, 200, upload)
      in [:get, %r{\A/[^/]+/listings/[^/]+\z}]
        json({ error: { code: 404, message: "No listing" } }, 404)
      in [:put | :patch, %r{\A/[^/]+/listings/([^/]+)\z}]
        json(JSON.parse(request.body).merge(language: Regexp.last_match(1)))
      in [:get, %r{\A/[^/]+/listings/[^/]+/[^/]+\z}]
        json({ images: [] })
      in [:post, %r{\A/[^/]+/listings/[^/]+/[^/]+\z}]
        json({ image: { id: "image-1", sha256: "0" } }, 200, upload)
      else
        json({ error: { code: 501, message: "The fake Play doesn't know #{method} #{path}" } }, 501)
      end
    end
  end

  WebMock.enable!
  WebMock.disable_net_connect!
  stub_request(:post, %r{\Ahttps://(oauth2\.googleapis\.com/token|www\.googleapis\.com/oauth2/v4/token)\z})
    .to_return(json({ access_token: "fake-token", token_type: "Bearer", expires_in: 3600 }))
  stub_request(:any, API).to_return { |request| answer(request) }
end

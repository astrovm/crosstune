# How play.sh runs fastlane: always with review_guard.rb loaded first, so no commit can cancel a review, and
# no edit can send production and a testing track to review together.
#
#   ruby play.rb supply ARGS...
#     fastlane supply, as on the command line.
#   ruby play.rb has-version KEY PACKAGE TRACK CODE
#     Exits 0 when TRACK already holds CODE, and 3 when it doesn't. Any other status is an error. It only
#     reads: the edit it opens is deleted, never committed.
#   ruby play.rb edit KEY PACKAGE [--from TRACK --code CODE --to TRACK[,TRACK...]] [--listing DIR]
#                     [--notes DIR] [--check-only]
#     One edit: the release CODE on --from goes on to each --to track, with the listing in DIR (folders by
#     language, as play.sh makes them) and the release notes in DIR/en-US/changelogs/CODE.txt. Then the edit is
#     committed, or with --check-only only checked. supply can only promote to one track a run, so this drives
#     supply's own client and uploader in one edit.
require "optparse"
require_relative "review_guard"

module Crosstune
  module Play
    # supply's uploader, with the client that holds the edit in reach.
    class Edit
      def self.uploader_class
        @uploader_class ||= Class.new(Supply::Uploader) { public :client }
      end

      def initialize(options)
        Supply.config = FastlaneCore::Configuration.create(Supply::Options.available_options, options)
        @uploader = self.class.uploader_class.new
      end

      def client
        @uploader.client
      end

      def track_has?(package, track, code)
        client.begin_edit(package_name: package)
        codes = client.track_version_codes(track).map(&:to_s)
        client.abort_current_edit
        codes.include?(code)
      end

      # supply's promote_track, for each track, but one that skips a release with no version codes, such as a
      # draft started in Play Console, where supply's stops with a NoMethodError.
      def promote(from, code, to)
        release = client.tracks(from).first&.releases.to_a.find { |r| r.version_codes.to_a.map(&:to_s).include?(code) }
        FastlaneCore::UI.user_error!("Track '#{from}' has no release with version code #{code}") unless release

        to.each do |name|
          promoted = release.dup
          promoted.status = Supply::ReleaseStatus::COMPLETED
          promoted.user_fraction = nil
          track = client.tracks(name).first || Google::Apis::AndroidpublisherV3::Track.new(track: name)
          # Play keeps the track's other releases as they are.
          track.releases = [promoted]
          client.update_track(name, track)
        end
      end

      # Each language's words and pictures. sync_image_upload sends only the pictures that changed.
      def send_listing(listing)
        Dir.children(listing).sort.each do |language|
          @uploader.upload_metadata(language, client.listing_for_language(language))
          @uploader.upload_images(language)
          @uploader.upload_screenshots(language)
        end
      end

      def send_notes(track)
        @uploader.perform_upload_meta([], track)
      end
    end

    module_function

    def require_supply
      require "supply"
    end

    def has_version(argv)
      key, package, track, code = argv
      abort("usage: play.rb has-version KEY PACKAGE TRACK CODE") unless code
      require_supply
      edit = Edit.new(json_key: key, package_name: package)
      exit(edit.track_has?(package, track, code) ? 0 : 3)
    end

    def parse_edit(argv)
      key, package, *rest = argv
      options = { to: [] }
      OptionParser.new do |parser|
        parser.on("--from TRACK") { |value| options[:from] = value }
        parser.on("--code CODE") { |value| options[:code] = value }
        parser.on("--to TRACKS", Array) { |value| options[:to] = value.reject(&:empty?) }
        parser.on("--listing DIR") { |value| options[:listing] = value }
        parser.on("--notes DIR") { |value| options[:notes] = value }
        parser.on("--check-only") { options[:check_only] = true }
      end.parse!(rest)
      abort("usage: play.rb edit KEY PACKAGE ...") unless package && rest.empty?
      options.merge(key: key, package: package)
    end

    def check_edit(options)
      to = options[:to]
      abort("Name --from and --code with --to") if !to.empty? && !(options[:from] && options[:code])
      abort("Release notes go to one track at a time") if options[:notes] && to.size != 1
      abort("Release notes and the listing go in separate edits") if options[:notes] && options[:listing]
      abort("Nothing to send") if to.empty? && !options[:listing]
    end

    def edit(argv)
      options = parse_edit(argv)
      check_edit(options)
      require_supply
      edit = Edit.new(
        json_key: options[:key],
        package_name: options[:package],
        track: options[:from] || "internal",
        version_code: options[:code]&.to_i,
        metadata_path: options[:notes] || options[:listing],
        skip_upload_apk: true,
        skip_upload_aab: true,
        skip_upload_metadata: true,
        skip_upload_images: true,
        skip_upload_screenshots: true,
        sync_image_upload: true
      )
      edit.client.begin_edit(package_name: options[:package])
      edit.promote(options[:from], options[:code], options[:to]) unless options[:to].empty?
      edit.send_notes(options[:to].first) if options[:notes]
      edit.send_listing(options[:listing]) if options[:listing]
      if options[:check_only]
        edit.client.validate_current_edit!
        puts("Google Play would take it")
      else
        edit.client.commit_current_edit!
        puts("Sent to Google Play")
      end
    end

    def main(argv)
      case argv.shift
      when "supply"
        argv.unshift("supply")
        load Gem.bin_path("fastlane", "fastlane")
      when "has-version"
        has_version(argv)
      when "edit"
        edit(argv)
      else
        abort("usage: play.rb supply ARGS... | has-version KEY PACKAGE TRACK CODE | edit KEY PACKAGE ...")
      end
    end
  end
end

begin
  Crosstune::Play.main(ARGV)
rescue StandardError => e
  # Plainly, as fastlane's own command line prints its errors. Anything else is a bug here, with its stack trace.
  if defined?(FastlaneCore::Interface::FastlaneException) && e.is_a?(FastlaneCore::Interface::FastlaneException)
    warn("[!] #{e.message}")
  elsif e.is_a?(Google::Apis::Error)
    warn("[!] Google Play answered #{e.status_code}: #{e.body || e.message}")
  else
    raise
  end
  exit(1)
end
